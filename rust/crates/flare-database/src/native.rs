use crate::{
    Error, Observation,
    engine::State,
    publish,
    storage::{Batch, Kind, Query, Receipt, Value, wrong_kind},
};
use diesel::{RunQueryDsl, sql_types::Text};
use std::{
    collections::HashMap,
    path::PathBuf,
    sync::{
        Arc, Mutex, OnceLock, Weak,
        atomic::{AtomicU64, Ordering},
    },
};
use tokio::sync::{Semaphore, mpsc, oneshot, watch};

type Job = Box<dyn FnOnce(&mut State) + Send>;
type Ready = Option<Result<(), Error>>;
static OWNERS: OnceLock<Mutex<HashMap<PathBuf, Weak<Owner>>>> = OnceLock::new();

struct Owner {
    kind: Kind,
    queue: mpsc::UnboundedSender<Job>,
    slots: Arc<Semaphore>,
    ready: watch::Receiver<Ready>,
    next_observer: AtomicU64,
}

#[derive(Clone)]
pub(crate) struct Backend {
    owner: Arc<Owner>,
}

impl Backend {
    pub(crate) async fn open(path: PathBuf, kind: Kind) -> Result<Self, Error> {
        let path = blocking::unblock(move || {
            let name = path
                .file_name()
                .ok_or_else(|| Error::InvalidRequest("database file name required".into()))?
                .to_owned();
            let parent = path
                .parent()
                .filter(|p| !p.as_os_str().is_empty())
                .unwrap_or_else(|| std::path::Path::new("."));
            std::fs::create_dir_all(parent)?;
            let path = std::fs::canonicalize(parent)?.join(name);
            if path.exists() {
                Ok(std::fs::canonicalize(path)?)
            } else {
                Ok::<_, Error>(path)
            }
        })
        .await?;
        let owner = {
            let mut owners = OWNERS
                .get_or_init(Mutex::default)
                .lock()
                .unwrap_or_else(std::sync::PoisonError::into_inner);
            owners.retain(|_, owner| owner.strong_count() > 0);
            if let Some(owner) = owners
                .get(&path)
                .and_then(Weak::upgrade)
                .filter(|o| !o.queue.is_closed())
            {
                if owner.kind != kind {
                    return Err(wrong_kind());
                }
                owner
            } else {
                let (queue, mut receiver) = mpsc::unbounded_channel::<Job>();
                let (ready, readiness) = watch::channel(None);
                let owner = Arc::new(Owner {
                    kind,
                    queue,
                    slots: Arc::new(Semaphore::new(64)),
                    ready: readiness,
                    next_observer: AtomicU64::new(0),
                });
                let source = path.clone();
                let initializing = Arc::clone(&owner);
                std::thread::Builder::new()
                    .name(format!("flare-{}-database", kind.name()))
                    .spawn(move || {
                        let result = source
                            .to_str()
                            .ok_or_else(|| {
                                Error::InvalidRequest("database path is not UTF-8".into())
                            })
                            .and_then(|url| {
                                State::open(url, kind, |connection, version| {
                                    backup(connection, &source, version)
                                })
                            });
                        match result {
                            Ok(mut state) => {
                                ready.send_replace(Some(Ok(())));
                                drop(initializing);
                                while let Some(job) = receiver.blocking_recv() {
                                    job(&mut state);
                                }
                            }
                            Err(error) => {
                                ready.send_replace(Some(Err(error)));
                            }
                        }
                    })?;
                owners.insert(path, Arc::downgrade(&owner));
                owner
            }
        };
        let mut ready = owner.ready.clone();
        loop {
            if let Some(result) = ready.borrow_and_update().clone() {
                result?;
                return Ok(Self { owner });
            }
            ready.changed().await.map_err(|_| Error::WorkerStopped)?;
        }
    }

    pub(crate) async fn query(&self, query: Query) -> Result<Value, Error> {
        self.run(move |state| state.query(&query), true).await
    }

    pub(crate) async fn write(&self, mutations: Batch) -> Result<Receipt, Error> {
        self.run(move |state| state.write(mutations), false).await
    }

    async fn run<T: Send + 'static>(
        &self,
        job: impl FnOnce(&mut State) -> Result<T, Error> + Send + 'static,
        cancellable: bool,
    ) -> Result<T, Error> {
        let permit = Arc::clone(&self.owner.slots)
            .acquire_owned()
            .await
            .map_err(|_| Error::WorkerStopped)?;
        let (sender, receiver) = oneshot::channel();
        let owner = Arc::clone(&self.owner);
        self.owner
            .queue
            .send(Box::new(move |state| {
                let _permit = permit;
                // Registry reuse must also cover jobs whose original waiter was dropped.
                let _owner = owner;
                if !cancellable || !sender.is_closed() {
                    let _ = sender.send(job(state));
                }
            }))
            .map_err(|_| Error::WorkerStopped)?;
        receiver.await.map_err(|_| Error::WorkerStopped)?
    }

    pub(crate) async fn observe(&self, query: Query) -> Result<Observation, Error> {
        let permit = Arc::clone(&self.owner.slots)
            .acquire_owned()
            .await
            .map_err(|_| Error::WorkerStopped)?;
        let id = self
            .owner
            .next_observer
            .fetch_add(1, Ordering::Relaxed)
            .to_string();
        let (sender, receiver) = watch::channel(None);
        let job_id = id.clone();
        let owner = Arc::clone(&self.owner);
        self.owner
            .queue
            .send(Box::new(move |state| {
                let _permit = permit;
                let _owner = owner;
                if sender.is_closed() {
                    return;
                }
                state.observe(
                    job_id,
                    query,
                    Box::new(move |value| {
                        value.map_or_else(|| !sender.is_closed(), |value| publish(&sender, value))
                    }),
                );
            }))
            .map_err(|_| Error::WorkerStopped)?;
        Ok(Observation::new(receiver, self.clone(), id))
    }

    pub(crate) fn unsubscribe(&self, id: &str) {
        let id = id.to_owned();
        let owner = Arc::clone(&self.owner);
        // Cleanup must enqueue without blocking even when all work slots are occupied.
        let _ = self.owner.queue.send(Box::new(move |state| {
            let _owner = owner;
            state.unsubscribe(&id);
        }));
    }
}

fn backup(
    connection: &mut diesel::SqliteConnection,
    path: &std::path::Path,
    version: i32,
) -> Result<(), Error> {
    let parent = path
        .parent()
        .ok_or_else(|| Error::InvalidRequest("database parent required".into()))?;
    let name = path
        .file_name()
        .and_then(|s| s.to_str())
        .ok_or_else(|| Error::InvalidRequest("database file name required".into()))?;
    let temporary = tempfile::Builder::new()
        .prefix(&format!("{name}.pre-rust-v{version}-"))
        .suffix(".db")
        .tempfile_in(parent)?
        .into_temp_path();
    let target = temporary
        .to_str()
        .ok_or_else(|| Error::InvalidRequest("backup path is not UTF-8".into()))?;
    diesel::sql_query("VACUUM INTO ?")
        .bind::<Text, _>(target)
        .execute(connection)?;
    temporary
        .keep()
        .map_err(|e| Error::Io(e.error.to_string()))?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{AppDatabase, AppMutation, AppQuery, AppSnapshot, model::Account};
    use futures::{FutureExt, executor::block_on};
    use std::{sync::mpsc as sync_channel, time::Duration};

    #[test]
    fn unsubscribe_and_cancelled_registration_drain_even_when_work_slots_are_full() {
        use futures::StreamExt;
        let directory = tempfile::tempdir().unwrap();
        let backend = block_on(Backend::open(directory.path().join("app.db"), Kind::App)).unwrap();
        let app = AppDatabase {
            backend: backend.clone(),
        };
        let mut subscription = block_on(app.observe(AppQuery::Accounts)).unwrap();
        let snapshot = block_on(subscription.next()).unwrap().unwrap();
        let weak = Arc::downgrade(&snapshot);
        drop(snapshot);
        let (entered, started) = sync_channel::channel();
        let (release, released) = sync_channel::channel();
        assert!(
            backend
                .run(
                    move |_| {
                        entered.send(()).unwrap();
                        released.recv_timeout(Duration::from_secs(10)).unwrap();
                        Ok(())
                    },
                    false
                )
                .now_or_never()
                .is_none()
        );
        started.recv_timeout(Duration::from_secs(5)).unwrap();
        assert!(app.observe(AppQuery::Accounts).now_or_never().is_none());
        for _ in 0..62 {
            assert!(backend.run(|_| Ok(()), false).now_or_never().is_none());
        }
        assert_eq!(backend.owner.slots.available_permits(), 0);
        drop(subscription);
        release.send(()).unwrap();
        block_on(app.query(AppQuery::Accounts)).unwrap();
        assert!(weak.upgrade().is_none());
        assert!(block_on(backend.run(|state| Ok(state.observer_count()), true)).unwrap() == 0);
    }

    #[test]
    fn cancelled_writes_drain_on_original_owner_even_after_all_waiters_drop() {
        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join("app.db");
        let backend = block_on(Backend::open(path.clone(), Kind::App)).unwrap();
        let (entered, started) = sync_channel::channel();
        let (release, released) = sync_channel::channel();
        assert!(
            backend
                .run(
                    move |_| {
                        entered.send(()).unwrap();
                        released.recv_timeout(Duration::from_secs(10)).unwrap();
                        Ok(())
                    },
                    false
                )
                .now_or_never()
                .is_none()
        );
        started.recv_timeout(Duration::from_secs(5)).unwrap();
        let account = Account {
            account_key: "accepted".into(),
            credential_json: "{}".into(),
            platform_type: "x".into(),
            last_active: 0,
            sort_id: 0,
        };
        assert!(
            backend
                .write(Batch::App(vec![AppMutation::UpsertAccount(account)]))
                .now_or_never()
                .is_none()
        );
        drop(backend);
        // Reopening while an accepted job is pending must not create another owner.
        let reopened = block_on(AppDatabase::open(path)).unwrap();
        release.send(()).unwrap();
        assert!(matches!(
            block_on(reopened.query(AppQuery::Account("accepted".into()))).unwrap(),
            AppSnapshot::Account(Some(_))
        ));
        let next = block_on(reopened.write(vec![AppMutation::SetAccountLastActive {
            account_key: "accepted".into(),
            last_active: 1,
        }]))
        .unwrap();
        assert_eq!(next.revision, 2);
    }
}
