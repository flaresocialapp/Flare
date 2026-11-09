//! Rust-only `SQLite` storage. Platform business interfaces remain `ViewModels`.
//!
//! SQL runs on a native connection thread or a browser dedicated worker.
//! Database locations and application lifetime are managed by Rust application services.

pub mod agent;
pub mod cache;
mod engine;
mod migration;
pub mod model;
mod schema;
mod storage;
mod subscription;

#[cfg(not(target_arch = "wasm32"))]
mod native;
#[cfg(target_arch = "wasm32")]
mod web;

use serde::{Deserialize, Serialize};
use std::sync::Arc;

pub use agent::AgentDatabase;
pub use cache::CacheDatabase;
pub use model::{AppMutation, AppQuery, AppSnapshot, Commit};
use storage::{Batch, Kind, Query, Value};
use subscription::Observation;
pub use subscription::Subscription;

#[cfg(not(target_arch = "wasm32"))]
use native::Backend;
#[cfg(target_arch = "wasm32")]
use web::Backend;

/// Storage failure; SQL statements and bound credential values are never included.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize, thiserror::Error)]
pub enum Error {
    #[error("Database I/O failed: {0}")]
    Io(String),
    #[error("SQLite operation failed: {0}")]
    Sqlite(String),
    #[error("Unsupported database version: {0}")]
    UnsupportedVersion(i32),
    #[error("Database schema does not match the supported layout: {0}")]
    Schema(String),
    #[error("Invalid database request: {0}")]
    InvalidRequest(String),
    #[error("Database worker stopped")]
    WorkerStopped,
    #[error("Database owner changed; a pending write may already have committed")]
    OwnerChanged,
    #[error("Browser database operation failed: {0}")]
    Browser(String),
    #[error("The conversation already has a running task")]
    RunBusy,
    #[error("The conversation run is no longer current")]
    StaleRun,
    #[error("The expected database state changed")]
    Conflict,
}

impl From<diesel::result::Error> for Error {
    fn from(error: diesel::result::Error) -> Self {
        Self::Sqlite(error.to_string())
    }
}

impl From<diesel::ConnectionError> for Error {
    fn from(error: diesel::ConnectionError) -> Self {
        Self::Sqlite(error.to_string())
    }
}

#[cfg(not(target_arch = "wasm32"))]
impl From<std::io::Error> for Error {
    fn from(error: std::io::Error) -> Self {
        Self::Io(error.to_string())
    }
}

/// A shared owner of one app database. No connection or ORM type crosses this interface.
#[derive(Clone)]
pub struct AppDatabase {
    backend: Backend,
}

impl AppDatabase {
    /// Open an explicit native file, migrating supported Room v3–v12 databases.
    /// Each canonical path has one connection thread, shared by cloned/reopened handles.
    ///
    /// # Errors
    /// Preserves unsupported/malformed files and returns their version/schema error.
    /// A consistent sibling backup is made before changing a legacy database.
    #[cfg(not(target_arch = "wasm32"))]
    pub async fn open(path: impl Into<std::path::PathBuf>) -> Result<Self, Error> {
        Ok(Self {
            backend: Backend::open(path.into(), Kind::App).await?,
        })
    }

    /// Open a new browser store through the bundled dedicated worker script.
    /// `worker_url` is the internal runtime asset, not a database interface for the UI.
    ///
    /// # Errors
    /// Returns initialization, persistence, owner, or browser errors.
    #[cfg(target_arch = "wasm32")]
    pub async fn open(name: &str, worker_url: &str) -> Result<Self, Error> {
        Ok(Self {
            backend: Backend::open(name, worker_url, Kind::App).await?,
        })
    }

    /// Read an owned snapshot. Cancellation may skip a read that has not started.
    ///
    /// # Errors
    /// Returns query or worker errors; absence is represented within the snapshot.
    pub async fn query(&self, query: AppQuery) -> Result<AppSnapshot, Error> {
        self.backend.query(Query::App(query)).await?.into_app()
    }

    /// Commit a batch atomically. Once admitted, writes survive cancellation of the waiter.
    /// Success means the outer transaction committed and subscriptions were refreshed.
    ///
    /// # Errors
    /// A validation/SQL/commit error rolls back the batch and publishes no new state.
    pub async fn write(&self, mutations: Vec<AppMutation>) -> Result<Commit, Error> {
        self.backend.write(Batch::App(mutations)).await?.app()
    }

    /// Observe the first and subsequent committed query results, suppressing equal values.
    /// Slow consumers receive the latest result; this is not a transaction event log.
    ///
    /// # Errors
    /// An initial query/registration failure is returned. Later failures are stream items.
    pub async fn observe(&self, query: AppQuery) -> Result<Subscription, Error> {
        let receiver = ready(self.backend.observe(Query::App(query)).await?).await?;
        Ok(Subscription::new(receiver, Value::app))
    }
}

type Snapshot = Result<Arc<Value>, Arc<Error>>;
type SnapshotSender = tokio::sync::watch::Sender<Option<Snapshot>>;
type SnapshotReceiver = tokio::sync::watch::Receiver<Option<Snapshot>>;

async fn ready(mut observation: Observation) -> Result<Observation, Error> {
    loop {
        let current = observation.receiver.borrow_and_update().clone();
        if let Some(value) = current {
            value.map_err(|error| (*error).clone())?;
            return Ok(observation);
        }
        observation
            .receiver
            .changed()
            .await
            .map_err(|_| Error::WorkerStopped)?;
    }
}

fn publish(sender: &SnapshotSender, value: Result<Value, Error>) -> bool {
    if sender.is_closed() {
        return false;
    }
    let value = value.map(Arc::new).map_err(Arc::new);
    sender.send_if_modified(|current| {
        if current.as_ref() == Some(&value) {
            return false;
        }
        *current = Some(value);
        true
    });
    true
}
