use crate::{AppSnapshot, Backend, Error, Snapshot, SnapshotReceiver, storage::Value};
use futures_core::Stream;
use std::{
    pin::Pin,
    sync::Arc,
    task::{Context, Poll},
};
use tokio_stream::wrappers::WatchStream;

pub(crate) struct Observation {
    pub(crate) receiver: SnapshotReceiver,
    registration: Registration,
}
impl Observation {
    pub(crate) fn new(receiver: SnapshotReceiver, backend: Backend, id: String) -> Self {
        Self {
            receiver,
            registration: Registration { backend, id },
        }
    }
}
struct Registration {
    backend: Backend,
    id: String,
}
impl Drop for Registration {
    fn drop(&mut self) {
        self.backend.unsubscribe(&self.id);
    }
}

/// Latest committed results. Dropping this stream releases its database handle.
pub struct Subscription<T = AppSnapshot> {
    stream: WatchStream<Option<Snapshot>>,
    _registration: Registration,
    extract: fn(&Value) -> Result<Arc<T>, Error>,
}

impl<T> Subscription<T> {
    pub(crate) fn new(
        observation: Observation,
        extract: fn(&Value) -> Result<Arc<T>, Error>,
    ) -> Self {
        Self {
            stream: WatchStream::new(observation.receiver),
            _registration: observation.registration,
            extract,
        }
    }
}

impl<T> Stream for Subscription<T> {
    type Item = Result<Arc<T>, Arc<Error>>;

    fn poll_next(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Option<Self::Item>> {
        let this = self.get_mut();
        loop {
            match Pin::new(&mut this.stream).poll_next(cx) {
                Poll::Ready(Some(None)) => {}
                Poll::Ready(Some(Some(value))) => {
                    return Poll::Ready(Some(
                        value.and_then(|value| (this.extract)(&value).map_err(Arc::new)),
                    ));
                }
                Poll::Ready(None) => return Poll::Ready(None),
                Poll::Pending => return Poll::Pending,
            }
        }
    }
}
