//! Agent persistence. Payload JSON belongs to Rust callers, not a particular LLM framework.

pub(crate) mod engine;
pub(crate) mod migration;
mod model;
mod schema;

use crate::{
    Backend, Error, Subscription, ready,
    storage::{Batch, Kind, Query as StorageQuery, Value},
};
pub use model::*;

/// Shared handle for one Agent store. Opening never resumes runs or invokes tools.
#[derive(Clone)]
pub struct AgentDatabase {
    backend: Backend,
}

impl AgentDatabase {
    /// Open a native Agent file. Recognized legacy Agent databases are rebuilt once.
    /// # Errors
    /// Unknown layouts, future versions, and I/O failures preserve the source and return errors.
    #[cfg(not(target_arch = "wasm32"))]
    pub async fn open(path: impl Into<std::path::PathBuf>) -> Result<Self, Error> {
        Ok(Self {
            backend: Backend::open(path.into(), Kind::Agent).await?,
        })
    }

    /// Open an independent browser Agent store through the internal worker runtime.
    /// # Errors
    /// Returns browser, persistence, or initialization errors.
    #[cfg(target_arch = "wasm32")]
    pub async fn open(name: &str, worker_url: &str) -> Result<Self, Error> {
        Ok(Self {
            backend: Backend::open(name, worker_url, Kind::Agent).await?,
        })
    }

    /// Read one consistent snapshot, including the message/checkpoint relationship.
    /// # Errors
    /// Returns query or worker errors.
    pub async fn query(&self, query: Query) -> Result<Snapshot, Error> {
        self.backend
            .query(StorageQuery::Agent(query))
            .await?
            .into_agent()
    }

    /// Commit a complete batch. Accepted writes survive cancellation of their waiter.
    /// # Errors
    /// A stale run, conflicting edit, invalid checkpoint or SQL error rolls back every mutation.
    pub async fn write(&self, mutations: Vec<Mutation>) -> Result<Commit, Error> {
        self.backend.write(Batch::Agent(mutations)).await?.agent()
    }

    /// Observe the first and subsequent committed results, suppressing equal values.
    /// # Errors
    /// Returns registration errors; subsequent errors are stream items.
    pub async fn observe(&self, query: Query) -> Result<Subscription<Snapshot>, Error> {
        let receiver = ready(self.backend.observe(StorageQuery::Agent(query)).await?).await?;
        Ok(Subscription::new(receiver, Value::agent))
    }
}
