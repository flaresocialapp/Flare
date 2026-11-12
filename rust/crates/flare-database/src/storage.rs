use crate::{AppMutation, AppQuery, AppSnapshot, Commit, Error, agent, cache};
use serde::{Deserialize, Serialize};
use std::sync::Arc;

#[derive(Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
pub(crate) enum Kind {
    #[default]
    App,
    Agent,
    Cache,
}

impl Kind {
    #[cfg(target_arch = "wasm32")]
    pub(crate) fn version(self) -> i32 {
        match self {
            Self::App => crate::migration::VERSION,
            Self::Agent => agent::migration::VERSION,
            Self::Cache => cache::migration::VERSION,
        }
    }
    pub(crate) fn name(self) -> &'static str {
        match self {
            Self::App => "app",
            Self::Agent => "agent",
            Self::Cache => "cache",
        }
    }
}

#[derive(Clone, Serialize, Deserialize)]
pub(crate) enum Query {
    App(AppQuery),
    Agent(agent::Query),
    Cache(cache::Query),
}
#[derive(Serialize, Deserialize)]
pub(crate) enum Batch {
    App(Vec<AppMutation>),
    Agent(Vec<agent::Mutation>),
    Cache {
        epoch: String,
        mutations: Vec<cache::Mutation>,
    },
}
#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
pub(crate) enum Value {
    App(Arc<AppSnapshot>),
    Agent(Arc<agent::Snapshot>),
    Cache(Arc<cache::Snapshot>),
}
#[derive(Serialize, Deserialize)]
pub(crate) enum Receipt {
    App(Commit),
    Agent(agent::Commit),
    Cache(cache::Commit),
}

impl Query {
    pub(crate) fn kind(&self) -> Kind {
        match self {
            Self::App(_) => Kind::App,
            Self::Agent(_) => Kind::Agent,
            Self::Cache(_) => Kind::Cache,
        }
    }
}
impl Batch {
    pub(crate) fn kind(&self) -> Kind {
        match self {
            Self::App(_) => Kind::App,
            Self::Agent(_) => Kind::Agent,
            Self::Cache { .. } => Kind::Cache,
        }
    }
    pub(crate) fn is_empty(&self) -> bool {
        match self {
            Self::App(rows) => rows.is_empty(),
            Self::Agent(rows) => rows.is_empty(),
            Self::Cache { .. } => false,
        }
    }
}
impl Value {
    pub(crate) fn into_app(self) -> Result<AppSnapshot, Error> {
        match self {
            Self::App(value) => Ok(Arc::unwrap_or_clone(value)),
            _ => Err(wrong_kind()),
        }
    }
    pub(crate) fn into_agent(self) -> Result<agent::Snapshot, Error> {
        match self {
            Self::Agent(value) => Ok(Arc::unwrap_or_clone(value)),
            _ => Err(wrong_kind()),
        }
    }
    pub(crate) fn app(&self) -> Result<Arc<AppSnapshot>, Error> {
        match self {
            Self::App(value) => Ok(Arc::clone(value)),
            _ => Err(wrong_kind()),
        }
    }
    pub(crate) fn agent(&self) -> Result<Arc<agent::Snapshot>, Error> {
        match self {
            Self::Agent(value) => Ok(Arc::clone(value)),
            _ => Err(wrong_kind()),
        }
    }
    pub(crate) fn into_cache(self) -> Result<cache::Snapshot, Error> {
        match self {
            Self::Cache(value) => Ok(Arc::unwrap_or_clone(value)),
            _ => Err(wrong_kind()),
        }
    }
    pub(crate) fn cache(&self) -> Result<Arc<cache::Snapshot>, Error> {
        match self {
            Self::Cache(value) => Ok(Arc::clone(value)),
            _ => Err(wrong_kind()),
        }
    }
}
impl Receipt {
    pub(crate) fn app(self) -> Result<Commit, Error> {
        match self {
            Self::App(value) => Ok(value),
            _ => Err(wrong_kind()),
        }
    }
    pub(crate) fn agent(self) -> Result<agent::Commit, Error> {
        match self {
            Self::Agent(value) => Ok(value),
            _ => Err(wrong_kind()),
        }
    }
    pub(crate) fn cache(self) -> Result<cache::Commit, Error> {
        match self {
            Self::Cache(value) => Ok(value),
            _ => Err(wrong_kind()),
        }
    }
}
pub(crate) fn wrong_kind() -> Error {
    Error::Schema("database kind mismatch".into())
}
