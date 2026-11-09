#![cfg(target_arch = "wasm32")]

use std::sync::Arc;

use flare_core::application::{AppServices, InitConfig, InitError, initialize, services};
use flare_database::{AppMutation, AppQuery, AppSnapshot, Error, model::SearchHistory};
use flare_datastore::stores::APP_SETTINGS;
use futures::future::try_join_all;
use wasm_bindgen_test::{wasm_bindgen_test, wasm_bindgen_test_configure};

wasm_bindgen_test_configure!(run_in_browser);

#[wasm_bindgen_test]
#[allow(
    clippy::too_many_lines,
    reason = "Complete browser startup and isolated dependency lifecycle"
)]
async fn browser_initialization_is_shared_and_survives_cancellation() {
    let worker_url = web_sys::Url::new_with_base(
        "/target/database-web/worker.js",
        &web_sys::window().unwrap().location().href().unwrap(),
    )
    .unwrap()
    .href();
    let config = InitConfig {
        web_database_worker_url: Some(worker_url),
        ..Default::default()
    };
    assert!(matches!(services(), Err(InitError::NotInitialized)));
    assert!(matches!(
        initialize(InitConfig {
            android_files_dir: Some("/android-only".into()),
            ..config.clone()
        })
        .await,
        Err(InitError::InvalidConfiguration { .. })
    ));
    for url in [
        None,
        Some("relative/worker.js".into()),
        Some("http://[".into()),
        Some("data:text/javascript,invalid".into()),
    ] {
        assert!(matches!(
            initialize(InitConfig {
                web_database_worker_url: url,
                ..Default::default()
            })
            .await,
            Err(InitError::InvalidConfiguration { .. })
        ));
    }
    js_sys::eval("globalThis.databaseWorkers=[]; const Original=globalThis.Worker; globalThis.Worker=class extends Original { constructor(...args) { const kind=new URL(args[0],location.href).searchParams.get('kind'); if((kind==='agent'&&globalThis.failAgent)||(kind==='cache'&&globalThis.failCache))throw new Error('injected database startup failure'); super(...args); this.databaseKind=kind; databaseWorkers.push(this); } };").unwrap();
    let mut cancelled = Box::pin(initialize(config.clone()));
    assert!(futures::poll!(cancelled.as_mut()).is_pending());
    drop(cancelled);
    try_join_all((0..16).map(|_| initialize(config.clone())))
        .await
        .unwrap();
    let first = services().unwrap();
    assert_eq!(
        first
            .datastore
            .read(APP_SETTINGS)
            .await
            .unwrap()
            .version
            .as_deref(),
        Some("")
    );
    initialize(config.clone()).await.unwrap();
    assert!(Arc::ptr_eq(&first, &services().unwrap()));

    assert_eq!(
        first
            .app_database
            .write(vec![AppMutation::UpsertSearchHistory(SearchHistory {
                search: "shared".into(),
                created_at: 9_007_199_254_740_993,
            })])
            .await
            .unwrap()
            .revision,
        1
    );
    assert!(
        matches!(services().unwrap().app_database.query(AppQuery::SearchHistory).await.unwrap(),
        AppSnapshot::SearchHistory(rows) if rows[0].created_at == 9_007_199_254_740_993)
    );
    let mut conflicting = config.clone();
    conflicting
        .web_database_worker_url
        .as_mut()
        .unwrap()
        .push_str("?different-bundle");
    assert!(matches!(
        initialize(conflicting).await,
        Err(InitError::ConfigurationConflict)
    ));
    agent_is_lazy_and_failure_isolated(&first).await;
    cache_is_lazy_and_failure_isolated(&first).await;
    agent_worker_failure_is_isolated(&first).await;

    // A fatal worker failure must not silently replace repository-held handles.
    js_sys::eval("databaseWorkers[0].dispatchEvent(new ErrorEvent('error', { message:'injected worker failure', cancelable:true }));").unwrap();
    let error = first
        .app_database
        .query(AppQuery::Accounts)
        .await
        .unwrap_err();
    assert!(matches!(error, Error::Browser(_) | Error::WorkerStopped));
    initialize(config).await.unwrap();
    assert!(Arc::ptr_eq(&first, &services().unwrap()));
    assert_eq!(
        first
            .app_database
            .query(AppQuery::Accounts)
            .await
            .unwrap_err(),
        Error::WorkerStopped
    );
}

async fn cache_is_lazy_and_failure_isolated(first: &Arc<AppServices>) {
    use flare_database::cache::{Mutation, Query, Snapshot};
    assert!(
        js_sys::eval("databaseWorkers.every(w=>w.databaseKind!=='cache')")
            .unwrap()
            .as_bool()
            .unwrap()
    );
    js_sys::eval("globalThis.failCache=true").unwrap();
    assert!(matches!(
        first.cache_database().await,
        Err(InitError::Database { .. })
    ));
    first.app_database.query(AppQuery::Accounts).await.unwrap();
    js_sys::eval("globalThis.failCache=false").unwrap();
    let mut cancelled = Box::pin(first.cache_database());
    assert!(futures::poll!(cancelled.as_mut()).is_pending());
    drop(cancelled);
    let handles = try_join_all((0..16).map(|_| first.cache_database()))
        .await
        .unwrap();
    let Snapshot::Info(info) = handles[0].query(Query::Info).await.unwrap() else {
        panic!()
    };
    assert_eq!(
        handles[0]
            .write(
                &info.epoch,
                vec![Mutation::ResetTimeline {
                    paging_key: "home".into(),
                    account_keys: vec![]
                }]
            )
            .await
            .unwrap()
            .revision,
        1
    );
    let Snapshot::Records(rows) = handles[15]
        .query(Query::PagingKey("home".into()))
        .await
        .unwrap()
    else {
        panic!()
    };
    assert_eq!(rows.len(), 1);
    first
        .agent_database()
        .await
        .unwrap()
        .query(flare_database::agent::Query::Conversations)
        .await
        .unwrap();
    js_sys::eval("databaseWorkers.find(w=>w.databaseKind==='cache').dispatchEvent(new ErrorEvent('error',{message:'Cache worker stopped',cancelable:true}))").unwrap();
    assert!(handles[0].query(Query::Info).await.is_err());
    assert_eq!(
        first
            .cache_database()
            .await
            .unwrap()
            .query(Query::Info)
            .await
            .unwrap_err(),
        Error::WorkerStopped
    );
    first.app_database.query(AppQuery::Accounts).await.unwrap();
    first
        .agent_database()
        .await
        .unwrap()
        .query(flare_database::agent::Query::Conversations)
        .await
        .unwrap();
}

async fn agent_is_lazy_and_failure_isolated(first: &Arc<AppServices>) {
    use flare_database::agent::Mutation;
    assert!(
        js_sys::eval("databaseWorkers.every(worker=>worker.databaseKind!=='agent')")
            .unwrap()
            .as_bool()
            .unwrap()
    );
    js_sys::eval("globalThis.failAgent=true").unwrap();
    assert!(matches!(
        first.agent_database().await,
        Err(InitError::Database { .. })
    ));
    first.app_database.query(AppQuery::Accounts).await.unwrap();
    assert!(Arc::ptr_eq(first, &services().unwrap()));
    js_sys::eval("globalThis.failAgent=false").unwrap();
    let mut cancelled = Box::pin(first.agent_database());
    assert!(futures::poll!(cancelled.as_mut()).is_pending());
    drop(cancelled);
    let handles = try_join_all((0..16).map(|_| first.agent_database()))
        .await
        .unwrap();
    let receipt = handles[0]
        .write(vec![Mutation::ClaimRun {
            conversation_id: "c".into(),
            now: 1,
        }])
        .await
        .unwrap();
    assert_eq!(receipt.revision, 1);
    assert_eq!(
        handles[15]
            .write(vec![Mutation::ClaimRun {
                conversation_id: "c".into(),
                now: 1
            }])
            .await
            .unwrap_err(),
        Error::RunBusy
    );
}

async fn agent_worker_failure_is_isolated(first: &Arc<AppServices>) {
    use flare_database::agent::Query;
    js_sys::eval("databaseWorkers.find(worker=>worker.databaseKind==='agent').dispatchEvent(new ErrorEvent('error',{message:'Agent worker stopped',cancelable:true}))").unwrap();
    assert!(
        first
            .agent_database()
            .await
            .unwrap()
            .query(Query::Conversations)
            .await
            .is_err()
    );
    assert_eq!(
        first
            .agent_database()
            .await
            .unwrap()
            .query(Query::Conversations)
            .await
            .unwrap_err(),
        Error::WorkerStopped
    );
    first.app_database.query(AppQuery::Accounts).await.unwrap();
    assert!(Arc::ptr_eq(first, &services().unwrap()));
}
