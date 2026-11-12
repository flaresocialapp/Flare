//! Rebuildable cache storage. Schema v4 adds indexed account scans and room updates to v3.
//! Observe timeline identities first, then load the changed page in one consistent read transaction.

pub(crate) mod engine;
mod legacy_triggers;
pub(crate) mod migration;
mod model;
mod schema;
mod triggers;
pub use model::*;

use crate::{
    Backend, Error, Subscription, ready,
    storage::{Batch, Kind, Query as StorageQuery, Value},
};

/// A shared cache owner. Opening does not send messages, translate, or fetch network data.
#[derive(Clone)]
pub struct CacheDatabase {
    backend: Backend,
}

impl CacheDatabase {
    /// Open a cache file. Recognized legacy caches are rebuilt transactionally once.
    /// # Errors
    /// Unknown databases, future versions and I/O failures are preserved and reported.
    #[cfg(not(target_arch = "wasm32"))]
    pub async fn open(path: impl Into<std::path::PathBuf>) -> Result<Self, Error> {
        Ok(Self {
            backend: Backend::open(path.into(), Kind::Cache).await?,
        })
    }
    /// Open a separate browser cache through the internal database worker.
    /// # Errors
    /// Returns persistence, browser, or initialization errors.
    #[cfg(target_arch = "wasm32")]
    pub async fn open(name: &str, worker_url: &str) -> Result<Self, Error> {
        Ok(Self {
            backend: Backend::open(name, worker_url, Kind::Cache).await?,
        })
    }
    /// Read an owned snapshot; page hydration shares a single read transaction.
    /// # Errors
    /// Returns validation, query, or worker errors.
    pub async fn query(&self, query: Query) -> Result<Snapshot, Error> {
        self.backend
            .query(StorageQuery::Cache(query))
            .await?
            .into_cache()
    }
    /// Commit a batch against the epoch captured before the work started.
    /// Read `Query::Info` before a network request; a clear invalidates older epochs.
    /// # Errors
    /// A stale epoch/page, conflicting update, validation or SQL failure rolls back the whole batch.
    pub async fn write(
        &self,
        epoch: impl Into<String>,
        mutations: Vec<Mutation>,
    ) -> Result<Commit, Error> {
        self.backend
            .write(Batch::Cache {
                epoch: epoch.into(),
                mutations,
            })
            .await?
            .cache()
    }
    /// Rebase a pure, synchronous status edit after a version conflict, at most eight times.
    /// The function may run repeatedly; keep network calls and side effects outside it.
    /// # Errors
    /// A missing row, changed epoch or exhausted conflicts returns `Error::Conflict`.
    /// Identity changes and invalid payloads are rejected; deleted rows are never inserted.
    pub async fn update_status(
        &self,
        epoch: &str,
        id: &str,
        update: impl Fn(&mut Status) -> Result<(), Error> + Send,
    ) -> Result<(), Error> {
        for _ in 0..8 {
            let Snapshot::Info(info) = self.query(Query::Info).await? else {
                return Err(crate::storage::wrong_kind());
            };
            if info.epoch != epoch {
                return Err(Error::Conflict);
            }
            let Snapshot::Status(Some(mut status)) = self.query(Query::Status(id.into())).await?
            else {
                return Err(Error::Conflict);
            };
            let expected_version = status.version.clone();
            let identity = (
                status.id.clone(),
                status.account.clone(),
                status.status_key.clone(),
            );
            update(&mut status)?;
            if identity
                != (
                    status.id.clone(),
                    status.account.clone(),
                    status.status_key.clone(),
                )
            {
                return Err(Error::InvalidRequest(
                    "status edits cannot change identity".into(),
                ));
            }
            match self
                .write(
                    epoch,
                    vec![Mutation::UpdateStatus {
                        expected_version,
                        status,
                    }],
                )
                .await
            {
                Ok(_) => return Ok(()),
                Err(Error::Conflict) => {}
                Err(e) => return Err(e),
            }
        }
        Err(Error::Conflict)
    }
    /// Observe first and subsequent committed values, deduplicating equal results.
    /// # Errors
    /// Returns registration errors; later failures are stream items.
    pub async fn observe(&self, query: Query) -> Result<Subscription<Snapshot>, Error> {
        let receiver = ready(self.backend.observe(StorageQuery::Cache(query)).await?).await?;
        Ok(Subscription::new(receiver, Value::cache))
    }
}
