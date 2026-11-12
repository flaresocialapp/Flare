#![cfg(not(target_arch = "wasm32"))]
use flare_database::{AgentDatabase, AppDatabase, AppQuery, agent};
use futures::{StreamExt, executor::block_on};
use std::sync::Arc;

#[test]
fn app_and_agent_observers_release_snapshots_without_a_write() {
    block_on(async {
        let directory = tempfile::tempdir().unwrap();
        let app = AppDatabase::open(directory.path().join("app.db"))
            .await
            .unwrap();
        let mut subscription = app.observe(AppQuery::Accounts).await.unwrap();
        let snapshot = subscription.next().await.unwrap().unwrap();
        let weak = Arc::downgrade(&snapshot);
        drop(snapshot);
        drop(subscription);
        app.query(AppQuery::Accounts).await.unwrap();
        assert!(weak.upgrade().is_none());
        let agent = AgentDatabase::open(directory.path().join("agent.db"))
            .await
            .unwrap();
        let mut subscription = agent.observe(agent::Query::Conversations).await.unwrap();
        let snapshot = subscription.next().await.unwrap().unwrap();
        let weak = Arc::downgrade(&snapshot);
        drop(snapshot);
        drop(subscription);
        agent.query(agent::Query::Conversations).await.unwrap();
        assert!(weak.upgrade().is_none());
    });
}
