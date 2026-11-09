#![cfg(not(target_arch = "wasm32"))]

use diesel::{
    Connection, QueryableByName, RunQueryDsl,
    connection::SimpleConnection,
    sql_types::{BigInt, Text},
};
use flare_database::{
    AppDatabase, AppMutation as M, AppQuery as Q, AppSnapshot as S, Error, model::*,
};
use futures::{FutureExt, StreamExt, executor::block_on, future::try_join_all};
use serde_json::Value;
use std::{fs, path::Path};

const EXPORTS: [&str; 10] = [
    include_str!(
        "../../../../shared/schemas/dev.dimension.flare.data.database.app.AppDatabase/3.json"
    ),
    include_str!(
        "../../../../shared/schemas/dev.dimension.flare.data.database.app.AppDatabase/4.json"
    ),
    include_str!(
        "../../../../shared/schemas/dev.dimension.flare.data.database.app.AppDatabase/5.json"
    ),
    include_str!(
        "../../../../shared/schemas/dev.dimension.flare.data.database.app.AppDatabase/6.json"
    ),
    include_str!(
        "../../../../shared/schemas/dev.dimension.flare.data.database.app.AppDatabase/7.json"
    ),
    include_str!(
        "../../../../shared/schemas/dev.dimension.flare.data.database.app.AppDatabase/8.json"
    ),
    include_str!(
        "../../../../shared/schemas/dev.dimension.flare.data.database.app.AppDatabase/9.json"
    ),
    include_str!(
        "../../../../shared/schemas/dev.dimension.flare.data.database.app.AppDatabase/10.json"
    ),
    include_str!(
        "../../../../shared/schemas/dev.dimension.flare.data.database.app.AppDatabase/11.json"
    ),
    include_str!(
        "../../../../shared/schemas/dev.dimension.flare.data.database.app.AppDatabase/12.json"
    ),
];
const CREDENTIAL: &str = "{ \"token\":\"FAKE-ONLY\",\"unknown\": {\"n\":18446744073709551615} }";

fn fixture(path: &Path, version: usize) -> diesel::SqliteConnection {
    let export: Value = serde_json::from_str(EXPORTS[version - 3]).unwrap();
    let database = &export["database"];
    let mut connection = diesel::SqliteConnection::establish(path.to_str().unwrap()).unwrap();
    connection.batch_execute("PRAGMA foreign_keys=ON;").unwrap();
    for entity in database["entities"].as_array().unwrap() {
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
    for query in database["setupQueries"].as_array().unwrap() {
        connection.batch_execute(query.as_str().unwrap()).unwrap();
    }
    connection
        .batch_execute(&format!("PRAGMA user_version={version};"))
        .unwrap();
    let sql = if version >= 7 {
        "INSERT INTO DbAccount(account_key,credential_json,platform_type,last_active,sort_id) VALUES(?,?,?,?,-9)"
    } else {
        "INSERT INTO DbAccount VALUES(?,?,?,?)"
    };
    diesel::sql_query(sql)
        .bind::<Text, _>("a\\@name@exa\\@mple")
        .bind::<Text, _>(CREDENTIAL)
        .bind::<Text, _>("unknown-plugin")
        .bind::<BigInt, _>(i64::MAX - 7)
        .execute(&mut connection)
        .unwrap();
    connection.batch_execute("INSERT INTO DbKeywordFilter(keyword,for_timeline,for_notification,for_search,expired_at) VALUES('保留',1,0,1,9223372036854775807); INSERT INTO DbSearchHistory VALUES('历史',-9223372036854775808);").unwrap();
    if version >= 10 {
        connection
            .batch_execute("UPDATE DbKeywordFilter SET is_regex=1")
            .unwrap();
    }
    if version <= 10 {
        connection
            .batch_execute(
                "INSERT INTO DbApplication VALUES('old.example','{\"fake\":true}','MASTODON',1);",
            )
            .unwrap();
    }
    if version >= 4 {
        let sql = if version < 5 {
            "INSERT INTO DbRssSources(id,url,title,lastUpdate) VALUES(37,'https://example.test/feed',NULL,-5)"
        } else if version < 8 {
            "INSERT INTO DbRssSources(id,url,title,lastUpdate,openInBrowser) VALUES(37,'https://example.test/feed',NULL,-5,1)"
        } else if version == 8 {
            "INSERT INTO DbRssSources(id,url,title,lastUpdate,openInBrowser,type) VALUES(37,'https://example.test/feed',NULL,-5,1,'PLUGIN_FEED')"
        } else {
            "INSERT INTO DbRssSources(id,url,title,lastUpdate,displayMode,type) VALUES(37,'https://example.test/feed',NULL,-5,'DESCRIPTION_ONLY','PLUGIN_FEED')"
        };
        connection.batch_execute(sql).unwrap();
    }
    if version >= 6 {
        connection.batch_execute("INSERT INTO DbDraftGroup VALUES('g','{\"text\":\"draft\",\"future\":null}',-1,2);
            INSERT INTO DbDraftTarget VALUES('g','raw@key','FAILED','',0,NULL,-1,2,'target');
            INSERT INTO DbDraftMedia VALUES('g','/unchanged/附件.bin',NULL,'OTHER','',3,-1,'media');").unwrap();
    }
    connection
}

#[derive(QueryableByName)]
struct Scalar {
    #[diesel(sql_type = BigInt)]
    value: i64,
}
fn scalar(connection: &mut diesel::SqliteConnection, sql: &str) -> i64 {
    diesel::sql_query(sql)
        .get_result::<Scalar>(connection)
        .unwrap()
        .value
}

#[test]
#[allow(
    clippy::too_many_lines,
    reason = "One end-to-end preservation check for each historical schema"
)]
fn all_room_versions_migrate_nonempty_data_and_keep_a_consistent_backup() {
    for version in 3..=12 {
        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join("app.db");
        drop(fixture(&path, version));
        let database =
            block_on(AppDatabase::open(&path)).unwrap_or_else(|e| panic!("v{version}: {e}"));
        let S::Account(Some(account)) =
            block_on(database.query(Q::Account("a\\@name@exa\\@mple".into()))).unwrap()
        else {
            panic!("account missing")
        };
        assert_eq!(account.credential_json, CREDENTIAL);
        assert_eq!(account.platform_type, "unknown-plugin");
        assert_eq!(account.last_active, i64::MAX - 7);
        assert_eq!(account.sort_id, if version >= 7 { -9 } else { 0 });
        assert_eq!(
            block_on(database.query(Q::KeywordFilters)).unwrap(),
            S::KeywordFilters(vec![KeywordFilter {
                keyword: "保留".into(),
                for_timeline: 1,
                for_notification: 0,
                for_search: 1,
                expired_at: i64::MAX,
                is_regex: i64::from(version >= 10),
            }])
        );
        assert_eq!(
            block_on(database.query(Q::SearchHistory)).unwrap(),
            S::SearchHistory(vec![SearchHistory {
                search: "历史".into(),
                created_at: i64::MIN,
            }])
        );
        if version >= 4 {
            let S::RssSource(Some(rss)) = block_on(database.query(Q::RssSource(37))).unwrap()
            else {
                panic!("RSS missing")
            };
            assert_eq!(
                rss.display_mode,
                if version >= 9 {
                    "DESCRIPTION_ONLY"
                } else if version >= 5 {
                    "OPEN_IN_BROWSER"
                } else {
                    "FULL_CONTENT"
                }
            );
            assert_eq!(
                rss.subscription_type,
                if version >= 8 { "PLUGIN_FEED" } else { "RSS" }
            );
            assert_eq!(rss.last_update, -5);
        }
        if version >= 6 {
            let S::Draft(Some(draft)) = block_on(database.query(Q::Draft("g".into()))).unwrap()
            else {
                panic!("draft missing")
            };
            assert_eq!(draft.targets.len(), 1);
            assert_eq!(draft.media[0].cache_path, "/unchanged/附件.bin");
            assert_eq!(draft.group.content, "{\"text\":\"draft\",\"future\":null}");
        }
        let backups: Vec<_> = fs::read_dir(directory.path())
            .unwrap()
            .map(Result::unwrap)
            .filter(|f| f.file_name().to_string_lossy().contains(".pre-rust-"))
            .collect();
        assert_eq!(backups.len(), 1);
        let mut backup =
            diesel::SqliteConnection::establish(backups[0].path().to_str().unwrap()).unwrap();
        assert_eq!(
            scalar(
                &mut backup,
                "SELECT user_version AS value FROM pragma_user_version"
            ),
            i64::try_from(version).unwrap()
        );
        assert_eq!(
            scalar(&mut backup, "SELECT COUNT(*) AS value FROM DbAccount"),
            1
        );
        let mut current = diesel::SqliteConnection::establish(path.to_str().unwrap()).unwrap();
        assert_eq!(
            scalar(
                &mut current,
                "SELECT user_version AS value FROM pragma_user_version"
            ),
            13
        );
        if version <= 10 {
            assert_eq!(
                scalar(&mut current, "SELECT COUNT(*) AS value FROM DbApplication"),
                1
            );
        }
        block_on(database.write(vec![M::SetAccountLastActive {
            account_key: account.account_key.clone(),
            last_active: 42,
        }]))
        .unwrap();
        drop(database);
        let reopened = block_on(AppDatabase::open(&path)).unwrap();
        let S::Account(Some(after)) =
            block_on(reopened.query(Q::Account(account.account_key))).unwrap()
        else {
            panic!()
        };
        assert_eq!(after.last_active, 42);
        assert_eq!(after.credential_json, CREDENTIAL);
        assert_eq!(
            fs::read_dir(directory.path())
                .unwrap()
                .filter_map(Result::ok)
                .filter(|f| f.file_name().to_string_lossy().contains(".pre-rust-"))
                .count(),
            1
        );
    }
}

#[test]
fn wrong_version_identity_index_or_foreign_key_is_rejected_without_modifying_the_file() {
    for change in [
        "PRAGMA user_version=2",
        "PRAGMA user_version=13",
        "ALTER TABLE DbRssSources ADD COLUMN openInBrowser TEXT; PRAGMA user_version=13; PRAGMA application_id=0x464c4152",
        "UPDATE room_master_table SET identity_hash='wrong'",
        "DROP INDEX index_DbDraftTarget_group_id_account_key",
        "PRAGMA foreign_keys=OFF; ALTER TABLE DbDraftTarget RENAME TO tmp; CREATE TABLE DbDraftTarget AS SELECT * FROM tmp; DROP TABLE tmp;",
    ] {
        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join("app.db");
        let mut connection = fixture(&path, 12);
        connection.batch_execute(change).unwrap();
        drop(connection);
        let before = fs::read(&path).unwrap();
        assert!(
            block_on(AppDatabase::open(&path)).is_err(),
            "accepted {change}"
        );
        assert_eq!(fs::read(&path).unwrap(), before, "modified {change}");
    }
}

fn draft() -> Draft {
    Draft {
        group: DraftGroup {
            group_id: "g".into(),
            content: "{\"text\":\"original\"}".into(),
            created_at: 1,
            updated_at: 1,
        },
        targets: vec![DraftTarget {
            group_id: "g".into(),
            account_key: "raw-key".into(),
            status: "DRAFT".into(),
            error_message: None,
            attempt_count: 0,
            last_attempt_at: None,
            created_at: 1,
            updated_at: 1,
            target_id: "t".into(),
        }],
        media: vec![DraftMedia {
            group_id: "g".into(),
            cache_path: "/untouched/file".into(),
            file_name: None,
            media_type: "IMAGE".into(),
            alt_text: Some(String::new()),
            sort_order: 0,
            created_at: 1,
            media_id: "m".into(),
        }],
    }
}

#[test]
fn draft_upsert_keeps_children_and_failed_batch_has_no_publication() {
    block_on(async {
        let directory = tempfile::tempdir().unwrap();
        let database = AppDatabase::open(directory.path().join("app.db"))
            .await
            .unwrap();
        let mut changes = database.observe(Q::VisibleDrafts).await.unwrap();
        assert_eq!(*changes.next().await.unwrap().unwrap(), S::Drafts(vec![]));
        let original = draft();
        database
            .write(vec![M::SaveDraft(original.clone())])
            .await
            .unwrap();
        assert_eq!(
            *changes.next().await.unwrap().unwrap(),
            S::Drafts(vec![original.clone()])
        );
        let mut group = original.group.clone();
        group.updated_at = 2;
        database
            .write(vec![M::UpsertDraftGroup(group.clone())])
            .await
            .unwrap();
        let S::Draft(Some(after)) = database.query(Q::Draft("g".into())).await.unwrap() else {
            panic!()
        };
        assert_eq!(after.targets, original.targets);
        assert_eq!(after.media, original.media);
        changes.next().await.unwrap().unwrap();
        database
            .write(vec![M::UpsertDraftGroup(group)])
            .await
            .unwrap();
        assert!(
            changes.next().now_or_never().is_none(),
            "equal result was published"
        );
        let mut invalid = original.targets[0].clone();
        invalid.group_id = "missing".into();
        assert!(
            database
                .write(vec![
                    M::DeleteDraft("g".into()),
                    M::UpsertDraftTarget(invalid)
                ])
                .await
                .is_err()
        );
        assert!(
            changes.next().now_or_never().is_none(),
            "rollback was published"
        );
        assert!(matches!(
            database.query(Q::Draft("g".into())).await.unwrap(),
            S::Draft(Some(_))
        ));
        database
            .write(vec![M::DeleteDraft("g".into())])
            .await
            .unwrap();
        assert_eq!(*changes.next().await.unwrap().unwrap(), S::Drafts(vec![]));
    });
}

#[test]
fn all_tables_roundtrip_unknown_strings_nulls_and_large_integers() {
    block_on(async {
        let directory = tempfile::tempdir().unwrap();
        let database = AppDatabase::open(directory.path().join("app.db"))
            .await
            .unwrap();
        let account = Account {
            account_key: "escaped\\@key@host".into(),
            credential_json: CREDENTIAL.into(),
            platform_type: "future".into(),
            last_active: i64::MAX,
            sort_id: -1,
        };
        let filter = KeywordFilter {
            keyword: "未来".into(),
            for_timeline: 1,
            for_notification: 0,
            for_search: 1,
            expired_at: i64::MAX,
            is_regex: 1,
        };
        let history = SearchHistory {
            search: "历史".into(),
            created_at: i64::MAX,
        };
        let rss = RssSource {
            id: 0,
            url: "https://example.test".into(),
            title: None,
            icon: Some(String::new()),
            display_mode: "FUTURE_MODE".into(),
            last_update: i64::MIN,
            subscription_type: "PLUGIN_FEED".into(),
        };
        let commit = database
            .write(vec![
                M::UpsertAccount(account.clone()),
                M::UpsertKeywordFilter(filter.clone()),
                M::UpsertSearchHistory(history.clone()),
                M::UpsertRssSource(rss.clone()),
            ])
            .await
            .unwrap();
        assert_eq!(commit.rss_ids.len(), 1);
        assert_eq!(
            database.query(Q::Accounts).await.unwrap(),
            S::Accounts(vec![account.clone()])
        );
        assert_eq!(
            database.query(Q::KeywordFilters).await.unwrap(),
            S::KeywordFilters(vec![filter])
        );
        assert_eq!(
            database.query(Q::SearchHistory).await.unwrap(),
            S::SearchHistory(vec![history])
        );
        let mut expected = rss;
        expected.id = commit.rss_ids[0];
        assert_eq!(
            database.query(Q::RssSource(expected.id)).await.unwrap(),
            S::RssSource(Some(expected))
        );
        assert!(!format!("{account:?}").contains("FAKE-ONLY"));
    });
}

#[test]
fn concurrent_opens_and_subscriptions_share_the_same_ordered_owner() {
    block_on(async {
        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join("app.db");
        drop(fixture(&path, 3));
        let handles = try_join_all((0..12).map(|_| AppDatabase::open(&path)))
            .await
            .unwrap();
        let mut changes = handles[0].observe(Q::Accounts).await.unwrap();
        changes.next().await.unwrap().unwrap();
        let results = try_join_all(handles.iter().enumerate().map(|(i, db)| {
            db.write(vec![M::UpsertAccount(Account {
                account_key: format!("new-{i}"),
                credential_json: "{}".into(),
                platform_type: "x".into(),
                last_active: 0,
                sort_id: 0,
            })])
        }))
        .await
        .unwrap();
        let mut revisions: Vec<_> = results.into_iter().map(|r| r.revision).collect();
        revisions.sort_unstable();
        assert_eq!(revisions, (1..=12).collect::<Vec<_>>());
        let S::Accounts(values) = &*changes.next().await.unwrap().unwrap() else {
            panic!()
        };
        assert_eq!(values.len(), 13);
    });
}

#[test]
fn sql_delight_version_three_is_not_mistaken_for_room_three() {
    let directory = tempfile::tempdir().unwrap();
    let path = directory.path().join("app.db");
    let mut connection = fixture(&path, 3);
    connection
        .batch_execute("DROP TABLE room_master_table")
        .unwrap();
    drop(connection);
    assert!(matches!(
        block_on(AppDatabase::open(&path)),
        Err(Error::Schema(_))
    ));
}

#[test]
fn quoted_schema_identifiers_are_inspected_safely_then_rejected() {
    let directory = tempfile::tempdir().unwrap();
    let path = directory.path().join("app.db");
    let mut connection = fixture(&path, 12);
    connection
        .batch_execute("CREATE INDEX \"odd\"\"index\" ON DbAccount(last_active)")
        .unwrap();
    drop(connection);
    assert!(matches!(
        block_on(AppDatabase::open(path)),
        Err(Error::Schema(_))
    ));
}

#[test]
fn draft_list_hydrates_relations_across_multiple_batches() {
    block_on(async {
        let directory = tempfile::tempdir().unwrap();
        let database = AppDatabase::open(directory.path().join("app.db"))
            .await
            .unwrap();
        let mutations = (0..505)
            .map(|index| {
                let mut value = draft();
                value.group.group_id = format!("group-{index}");
                value.group.updated_at = index;
                value.targets[0].group_id = value.group.group_id.clone();
                value.targets[0].target_id = format!("target-{index}");
                value.media[0].group_id = value.group.group_id.clone();
                value.media[0].media_id = format!("media-{index}");
                M::SaveDraft(value)
            })
            .collect();
        database.write(mutations).await.unwrap();
        let S::Drafts(values) = database.query(Q::VisibleDrafts).await.unwrap() else {
            panic!()
        };
        assert_eq!(values.len(), 505);
        for (index, draft) in values.iter().enumerate() {
            assert_eq!(draft.group.updated_at, 504 - i64::try_from(index).unwrap());
            assert_eq!(draft.targets.len(), 1);
            assert_eq!(draft.media.len(), 1);
            assert_eq!(draft.targets[0].group_id, draft.group.group_id);
            assert_eq!(draft.media[0].group_id, draft.group.group_id);
        }
    });
}

#[test]
fn sending_recovery_preserves_cutoff_null_and_group_update_semantics() {
    block_on(async {
        let directory = tempfile::tempdir().unwrap();
        let database = AppDatabase::open(directory.path().join("app.db"))
            .await
            .unwrap();
        let values = (0..3)
            .map(|index| {
                let mut value = draft();
                value.group.group_id = format!("g-{index}");
                value.targets[0].group_id = value.group.group_id.clone();
                value.targets[0].target_id = format!("t-{index}");
                value.targets[0].status = "SENDING".into();
                value.targets[0].last_attempt_at = match index {
                    0 => None,
                    1 => Some(49),
                    _ => Some(50),
                };
                value.media[0].group_id = value.group.group_id.clone();
                value.media[0].media_id = format!("m-{index}");
                M::SaveDraft(value)
            })
            .collect();
        database.write(values).await.unwrap();
        database
            .write(vec![M::RecoverDraftTargets {
                from_status: "SENDING".into(),
                to_status: "FAILED".into(),
                expired_before: Some(50),
                error_message: Some("expired".into()),
                updated_at: 100,
            }])
            .await
            .unwrap();
        let S::Drafts(sending) = database
            .query(Q::DraftsByStatus(vec!["SENDING".into()]))
            .await
            .unwrap()
        else {
            panic!()
        };
        assert_eq!(sending.len(), 1);
        assert_eq!(sending[0].group.group_id, "g-2");
        assert_eq!(sending[0].group.updated_at, 1);
        let S::Drafts(failed) = database.query(Q::VisibleDrafts).await.unwrap() else {
            panic!()
        };
        assert_eq!(failed.len(), 2);
        assert!(failed.iter().all(|d| d.group.updated_at == 100));
        assert_eq!(
            database
                .query(Q::DraftTargetCount {
                    group_id: "g-0".into(),
                    status: Some("FAILED".into())
                })
                .await
                .unwrap(),
            S::DraftTargetCount(1)
        );
        assert_eq!(
            database
                .query(Q::DraftGroupsForAccount("raw-key".into()))
                .await
                .unwrap(),
            S::DraftGroupIds(vec!["g-0".into(), "g-1".into(), "g-2".into()])
        );
        database
            .write(vec![
                M::UpdateDraftTargetStatus(TargetStatusUpdate {
                    target_id: "t-0".into(),
                    status: "SENDING".into(),
                    error_message: None,
                    attempt_count: 2,
                    last_attempt_at: None,
                    updated_at: 101,
                }),
                M::TouchDraft {
                    group_id: "g-0".into(),
                    updated_at: 101,
                },
                M::DeleteDraftMediaForGroup("g-0".into()),
            ])
            .await
            .unwrap();
        let S::Draft(Some(updated)) = database.query(Q::Draft("g-0".into())).await.unwrap() else {
            panic!()
        };
        assert_eq!(updated.targets[0].error_message, None);
        assert_eq!(updated.targets[0].attempt_count, 2);
        assert!(updated.media.is_empty());
    });
}

#[test]
fn keyword_queries_keep_expiry_and_or_predicates_from_the_dao() {
    block_on(async {
        let directory = tempfile::tempdir().unwrap();
        let database = AppDatabase::open(directory.path().join("app.db"))
            .await
            .unwrap();
        let filters = [("permanent", 0, 1), ("expired", 50, 1), ("future", 51, 0)];
        database
            .write(
                filters
                    .into_iter()
                    .map(|(keyword, expired_at, timeline)| {
                        M::UpsertKeywordFilter(KeywordFilter {
                            keyword: keyword.into(),
                            for_timeline: timeline,
                            for_notification: 0,
                            for_search: 1,
                            expired_at,
                            is_regex: 0,
                        })
                    })
                    .collect(),
            )
            .await
            .unwrap();
        let S::KeywordFilters(values) = database
            .query(Q::ActiveKeywordFilters {
                now: 50,
                flags: None,
            })
            .await
            .unwrap()
        else {
            panic!()
        };
        assert_eq!(
            values
                .iter()
                .map(|f| f.keyword.as_str())
                .collect::<Vec<_>>(),
            ["future", "permanent"]
        );
        let S::KeywordFilters(values) = database
            .query(Q::ActiveKeywordFilters {
                now: 50,
                flags: Some((1, -1, -1)),
            })
            .await
            .unwrap()
        else {
            panic!()
        };
        assert_eq!(values.len(), 1);
        assert_eq!(values[0].keyword, "permanent");
        database.write(vec![M::ClearKeywordFilters]).await.unwrap();
        assert_eq!(
            database
                .query(Q::KeywordFilter("permanent".into()))
                .await
                .unwrap(),
            S::KeywordFilter(None)
        );
    });
}

#[test]
fn legacy_backup_includes_commits_still_in_the_wal() {
    let directory = tempfile::tempdir().unwrap();
    let path = directory.path().join("app.db");
    let mut legacy = fixture(&path, 12);
    legacy
        .batch_execute(
            "PRAGMA journal_mode=WAL; PRAGMA wal_autocheckpoint=0;
        INSERT INTO DbSearchHistory VALUES('in-wal',123);",
        )
        .unwrap();
    assert!(
        fs::metadata(directory.path().join("app.db-wal"))
            .unwrap()
            .len()
            > 0
    );
    let _database = block_on(AppDatabase::open(&path)).unwrap();
    let backup = fs::read_dir(directory.path())
        .unwrap()
        .map(Result::unwrap)
        .find(|entry| entry.file_name().to_string_lossy().contains(".pre-rust-"))
        .unwrap();
    let mut backup = diesel::SqliteConnection::establish(backup.path().to_str().unwrap()).unwrap();
    assert_eq!(
        scalar(
            &mut backup,
            "SELECT COUNT(*) AS value FROM DbSearchHistory WHERE search='in-wal'"
        ),
        1
    );
}
