use super::{InitError, spawn};
use std::future::Future;
use std::sync::{Mutex, PoisonError};
use tokio::sync::watch;

type Attempt<T> = watch::Receiver<Option<Result<T, InitError>>>;

pub(super) struct Service<T> {
    attempt: Mutex<Option<Attempt<T>>>,
}

impl<T: Clone + Send + Sync + 'static> Service<T> {
    pub(super) fn new() -> Self {
        Self {
            attempt: Mutex::new(None),
        }
    }

    pub(super) async fn get(
        &self,
        build: impl Future<Output = Result<T, InitError>> + Send + 'static,
    ) -> Result<T, InitError> {
        let (mut receiver, start) = {
            let mut attempt = self.attempt.lock().unwrap_or_else(PoisonError::into_inner);
            if let Some(receiver) = attempt.as_ref().filter(|receiver| {
                let result = receiver.borrow();
                matches!(&*result, Some(Ok(_)))
                    || (result.is_none() && receiver.has_changed().is_ok())
            }) {
                (receiver.clone(), None)
            } else {
                let (sender, receiver) = watch::channel(None);
                *attempt = Some(receiver.clone());
                (receiver, Some(sender))
            }
        };
        if let Some(sender) = start {
            let failure = sender.clone();
            // Initialization belongs to the service, not an individual waiting ViewModel.
            if let Err(error) = spawn(async move {
                sender.send_replace(Some(build.await));
            }) {
                failure.send_replace(Some(Err(error)));
            }
        }
        loop {
            if let Some(result) = receiver.borrow_and_update().clone() {
                return result;
            }
            receiver
                .changed()
                .await
                .map_err(|_| InitError::Interrupted)?;
        }
    }
}
