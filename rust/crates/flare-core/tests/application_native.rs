#![cfg(target_os = "macos")]

use std::{fs, process::Command, sync::Arc};

use diesel::{Connection, RunQueryDsl, connection::SimpleConnection, sql_types::Text};
use flare_core::application::{AppServices, InitConfig, InitError, initialize, services};
use flare_database::{AppDatabase, AppMutation, AppQuery, AppSnapshot};
use flare_datastore::{DataStore, stores::APP_SETTINGS};
use futures::{StreamExt as _, executor::block_on, future::try_join_all};

const SETTINGS: &[u8] = include_bytes!("../../flare-datastore/tests/fixtures/app_settings.pb");

fn room_fixture(path: &std::path::Path) {
    let schema: serde_json::Value = serde_json::from_str(include_str!(
        "../../../../shared/schemas/dev.dimension.flare.data.database.app.AppDatabase/12.json"
    ))
    .unwrap();
    let mut connection = diesel::SqliteConnection::establish(path.to_str().unwrap()).unwrap();
    for entity in schema["database"]["entities"].as_array().unwrap() {
        let name = entity["tableName"].as_str().unwrap();
        connection
            .batch_execute(
                &entity["createSql"]
                    .as_str()
                    .unwrap()
                    .replace("${TABLE_NAME}", name),
            )
            .unwrap();
        for index in entity["indices"].as_array().into_iter().flatten() {
            connection
                .batch_execute(
                    &index["createSql"]
                        .as_str()
                        .unwrap()
                        .replace("${TABLE_NAME}", name),
                )
                .unwrap();
        }
    }
    for sql in schema["database"]["setupQueries"].as_array().unwrap() {
        connection.batch_execute(sql.as_str().unwrap()).unwrap();
    }
    connection.batch_execute("PRAGMA user_version=12;").unwrap();
    diesel::sql_query("INSERT INTO DbAccount VALUES('fixture',?,'plugin',0,0)")
        .bind::<Text, _>("{\"fake\":true,\"future\":null}")
        .execute(&mut connection)
        .unwrap();
}

#[cfg(feature = "native-ffi")]
fn initialize_via_callback(config: InitConfig) -> Result<(), InitError> {
    use flare_core::application::{InitializationObserver, begin_initialize};
    use std::{sync::mpsc, time::Duration};

    struct Observer(mpsc::Sender<Result<(), InitError>>);
    impl InitializationObserver for Observer {
        fn on_initialized(&self) {
            let _ = self.0.send(Ok(()));
        }

        fn on_failed(&self, error: InitError) {
            let _ = self.0.send(Err(error));
        }
    }

    let (sender, receiver) = mpsc::channel();
    begin_initialize(config, Arc::new(Observer(sender)))?;
    receiver.recv_timeout(Duration::from_secs(5)).unwrap()
}

#[test]
fn initializes_the_existing_store_and_retries_storage_failures() {
    let Some(user_home) = std::env::var_os("FLARE_APPLICATION_TEST_HOME") else {
        let user_home = tempfile::tempdir().unwrap();
        let status = Command::new(std::env::current_exe().unwrap())
            .args([
                "--exact",
                "initializes_the_existing_store_and_retries_storage_failures",
                "--nocapture",
            ])
            .env("CFFIXED_USER_HOME", user_home.path())
            .env("FLARE_APPLICATION_TEST_HOME", user_home.path())
            .status()
            .unwrap();
        assert!(status.success());
        return;
    };
    let root = std::path::PathBuf::from(user_home).join(".flare");
    block_on(async {
        assert!(matches!(services(), Err(InitError::NotInitialized)));
        fs::write(&root, b"blocked directory").unwrap();
        assert!(matches!(
            initialize(InitConfig::default()).await,
            Err(InitError::DataStore { .. })
        ));
        assert_eq!(fs::read(&root).unwrap(), b"blocked directory");
        assert!(matches!(services(), Err(InitError::NotInitialized)));
        #[cfg(feature = "native-ffi")]
        assert!(matches!(
            initialize_via_callback(InitConfig::default()),
            Err(InitError::DataStore { .. })
        ));
        fs::remove_file(&root).unwrap();
        fs::create_dir(&root).unwrap();
        fs::write(root.join(APP_SETTINGS.name()), SETTINGS).unwrap();

        let path = root.join("app.db");
        fs::write(&path, b"unrecognized database").unwrap();
        assert!(matches!(
            initialize(InitConfig::default()).await,
            Err(InitError::Database { .. })
        ));
        assert!(matches!(services(), Err(InitError::NotInitialized)));
        assert_eq!(fs::read(&path).unwrap(), b"unrecognized database");
        assert_eq!(fs::read(root.join(APP_SETTINGS.name())).unwrap(), SETTINGS);
        fs::remove_file(&path).unwrap();
        room_fixture(&path);

        let mut cancelled = Box::pin(initialize(InitConfig::default()));
        let _ = futures::poll!(cancelled.as_mut());
        drop(cancelled);

        try_join_all((0..16).map(|_| initialize(InitConfig::default())))
            .await
            .unwrap();
        let first = services().unwrap();
        initialize(InitConfig::default()).await.unwrap();
        #[cfg(feature = "native-ffi")]
        initialize_via_callback(InitConfig::default()).unwrap();
        assert!(Arc::ptr_eq(&first, &services().unwrap()));
        let mut from_application = first.datastore.subscribe(APP_SETTINGS).await.unwrap();
        let store = DataStore::open().await.unwrap();
        let mut from_datastore = store.subscribe(APP_SETTINGS).await.unwrap();
        assert!(Arc::ptr_eq(
            &from_application.next().await.unwrap().unwrap(),
            &from_datastore.next().await.unwrap().unwrap(),
        ));
        assert_eq!(fs::read(root.join(APP_SETTINGS.name())).unwrap(), SETTINGS);

        assert_database_migrated_and_shared(&first, &path).await;
        assert_agent_initialization_is_lazy_and_isolated(&first, &root).await;
        assert_cache_initialization_is_lazy_and_isolated(&first, &root).await;
    });
}

async fn assert_cache_initialization_is_lazy_and_isolated(
    first: &Arc<AppServices>,
    root: &std::path::Path,
) {
    use flare_database::{
        CacheDatabase,
        cache::{Mutation, Query, Snapshot},
    };
    let home = root.parent().unwrap();
    let path = home.join("Library/Caches/Flare/cache.db");
    assert!(!path.exists(), "startup must not open cache storage");
    fs::create_dir_all(&path).unwrap();
    assert!(matches!(
        first.cache_database().await,
        Err(InitError::Database { .. })
    ));
    first.app_database.query(AppQuery::Accounts).await.unwrap();
    first
        .agent_database()
        .await
        .unwrap()
        .query(flare_database::agent::Query::Conversations)
        .await
        .unwrap();
    assert_eq!(fs::read(root.join(APP_SETTINGS.name())).unwrap(), SETTINGS);
    fs::remove_dir(&path).unwrap();
    let mut cancelled = Box::pin(first.cache_database());
    let _ = futures::poll!(cancelled.as_mut());
    drop(cancelled);
    let handles = try_join_all((0..16).map(|_| first.cache_database()))
        .await
        .unwrap();
    let Snapshot::Info(info) = handles[0].query(Query::Info).await.unwrap() else {
        panic!()
    };
    let receipt = handles[0]
        .write(
            &info.epoch,
            vec![Mutation::ResetTimeline {
                paging_key: "home".into(),
                account_keys: vec!["fixture".into()],
            }],
        )
        .await
        .unwrap();
    assert_eq!(receipt.revision, 1);
    assert_eq!(
        handles[15]
            .query(Query::PagingKey("home".into()))
            .await
            .unwrap(),
        CacheDatabase::open(&path)
            .await
            .unwrap()
            .query(Query::PagingKey("home".into()))
            .await
            .unwrap()
    );
    handles[15]
        .write(&info.epoch, vec![Mutation::ResetCache])
        .await
        .unwrap();
    assert!(matches!(
        first
            .app_database
            .query(AppQuery::Account("fixture".into()))
            .await
            .unwrap(),
        AppSnapshot::Account(Some(_))
    ));
    first
        .agent_database()
        .await
        .unwrap()
        .query(flare_database::agent::Query::Conversations)
        .await
        .unwrap();
    assert!(!root.join("cache.db").exists());
}

async fn assert_agent_initialization_is_lazy_and_isolated(
    first: &Arc<AppServices>,
    root: &std::path::Path,
) {
    use flare_database::agent::{Mutation, Query, Snapshot};
    let path = root.join("agent.db");
    assert!(
        !path.exists(),
        "application startup must not open Agent storage"
    );
    fs::create_dir(&path).unwrap();
    let failures = futures::future::join_all((0..16).map(|_| first.agent_database())).await;
    assert!(
        failures
            .iter()
            .all(|value| matches!(value, Err(InitError::Database { .. })))
    );
    assert!(Arc::ptr_eq(first, &services().unwrap()));
    first.app_database.query(AppQuery::Accounts).await.unwrap();
    assert_eq!(fs::read(root.join(APP_SETTINGS.name())).unwrap(), SETTINGS);
    fs::remove_dir(&path).unwrap();
    let mut source = diesel::SqliteConnection::establish(path.to_str().unwrap()).unwrap();
    source.batch_execute("CREATE TABLE agent_conversations(conversationId TEXT); CREATE TABLE agent_messages(position INTEGER); CREATE TABLE room_master_table(id INTEGER); PRAGMA user_version=9; INSERT INTO agent_conversations VALUES('legacy');").unwrap();
    drop(source);
    let mut cancelled = Box::pin(first.agent_database());
    let _ = futures::poll!(cancelled.as_mut());
    drop(cancelled);
    let handles = try_join_all((0..16).map(|_| first.agent_database()))
        .await
        .unwrap();
    assert_eq!(
        handles[0].query(Query::Conversations).await.unwrap(),
        Snapshot::Conversations(vec![])
    );
    let claim = handles[0]
        .write(vec![Mutation::ClaimRun {
            conversation_id: "new".into(),
            now: 1,
        }])
        .await
        .unwrap();
    assert_eq!(claim.revision, 1);
    assert_eq!(
        handles[15]
            .write(vec![Mutation::ClaimRun {
                conversation_id: "new".into(),
                now: 1
            }])
            .await
            .unwrap_err(),
        flare_database::Error::RunBusy
    );
    assert_eq!(
        handles[15]
            .write(vec![Mutation::FinishRun {
                conversation_id: "new".into(),
                run_id: claim.runs[0].run_id.clone(),
                error_message: None,
                retry_json: None
            }])
            .await
            .unwrap()
            .revision,
        2
    );
    assert!(Arc::ptr_eq(first, &services().unwrap()));
}

async fn assert_database_migrated_and_shared(first: &AppServices, path: &std::path::Path) {
    let AppSnapshot::Account(Some(account)) = first
        .app_database
        .query(AppQuery::Account("fixture".into()))
        .await
        .unwrap()
    else {
        panic!("migrated account missing")
    };
    assert_eq!(account.credential_json, "{\"fake\":true,\"future\":null}");
    let mut changes = first
        .app_database
        .observe(AppQuery::Accounts)
        .await
        .unwrap();
    changes.next().await.unwrap().unwrap();
    let mutation = AppMutation::SetAccountLastActive {
        account_key: "fixture".into(),
        last_active: 42,
    };
    assert_eq!(
        first
            .app_database
            .write(vec![mutation.clone()])
            .await
            .unwrap()
            .revision,
        1
    );
    let snapshot = changes.next().await.unwrap().unwrap();
    assert!(matches!(snapshot.as_ref(), AppSnapshot::Accounts(rows) if rows[0].last_active == 42));
    let reopened = AppDatabase::open(path).await.unwrap();
    assert_eq!(reopened.write(vec![mutation]).await.unwrap().revision, 2);
    assert_eq!(
        fs::read_dir(path.parent().unwrap())
            .unwrap()
            .filter_map(Result::ok)
            .filter(|entry| entry
                .file_name()
                .to_string_lossy()
                .contains(".pre-rust-v12-"))
            .count(),
        1
    );
}
