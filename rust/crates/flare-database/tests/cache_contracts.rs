#![cfg(not(target_arch = "wasm32"))]
use flare_database::{
    CacheDatabase, Error,
    cache::{
        Mutation, Query, Range, Record, RecordPage, Snapshot, Status, Translation,
        TranslationResult, User, UserHistory, key,
    },
};
use futures::{FutureExt, StreamExt, executor::block_on};
use std::{
    collections::{BTreeMap, BTreeSet},
    sync::{
        Arc, Barrier,
        atomic::{AtomicUsize, Ordering},
    },
};

async fn fixture() -> (tempfile::TempDir, CacheDatabase, String) {
    let directory = tempfile::tempdir().unwrap();
    let db = CacheDatabase::open(directory.path().join("cache.db"))
        .await
        .unwrap();
    let Snapshot::Info(info) = db.query(Query::Info).await.unwrap() else {
        panic!()
    };
    (directory, db, info.epoch)
}
fn status() -> Status {
    Status {
        account: "a".into(),
        status_key: "s".into(),
        payload: "{\"likes\":0,\"bookmarks\":0}".into(),
        ..Default::default()
    }
}
async fn read_status(db: &CacheDatabase) -> Status {
    let Snapshot::Status(Some(row)) = db.query(Query::Status(key(&["a", "s"]))).await.unwrap()
    else {
        panic!()
    };
    row
}
fn range(offset: i64, descending: bool) -> Range {
    Range {
        offset,
        limit: 100,
        descending,
    }
}
fn queries(offset: i64, descending: bool) -> [Query; 3] {
    [
        Query::SearchStatuses {
            text: "%_\\".into(),
            range: range(offset, descending),
        },
        Query::SearchUsers {
            text: "%_\\".into(),
            range: range(offset, descending),
        },
        Query::UserHistory {
            account: Some("a".into()),
            range: range(offset, descending),
        },
    ]
}
async fn records(db: &CacheDatabase, q: Query) -> RecordPage {
    match db.query(q).await.unwrap() {
        Snapshot::RecordsPage(page) => page,
        Snapshot::StatusPage(page) => {
            let mut statuses: BTreeMap<_, _> = page
                .bundle
                .statuses
                .into_iter()
                .map(|s| (s.id.clone(), s))
                .collect();
            RecordPage {
                records: page
                    .root_ids
                    .into_iter()
                    .map(|id| Record::Status(statuses.remove(&id).unwrap()))
                    .collect(),
                users: vec![],
                has_more: page.has_more,
            }
        }
        _ => panic!(),
    }
}
fn record_id(record: &Record) -> String {
    match record {
        Record::Status(r) => r.id.clone(),
        Record::User(r) => r.user_key.clone(),
        Record::UserHistory(r) => key(&[&r.account, &r.user_key]),
        _ => panic!(),
    }
}

#[test]
fn search_and_history_pages_cover_all_matches_with_stable_ties() {
    block_on(async {
        let (_directory, db, epoch) = fixture().await;
        let mut writes = Vec::new();
        for index in 0..235 {
            let id = format!("{index:03}");
            writes.push(Mutation::Put(Record::Status(Status {
                account: "a".into(),
                status_key: id.clone(),
                payload: "{}".into(),
                text: Some("literal %_\\".into()),
                ..Default::default()
            })));
            writes.push(Mutation::Put(Record::User(User {
                user_key: id.clone(),
                name: "literal %_\\".into(),
                payload: "{}".into(),
                ..Default::default()
            })));
            for account in ["a", "b"] {
                writes.push(Mutation::Put(Record::UserHistory(UserHistory {
                    account: account.into(),
                    user_key: id.clone(),
                    last_visit: i64::MAX,
                })));
            }
        }
        writes.push(Mutation::Put(Record::User(User {
            user_key: "decoy".into(),
            name: "does not match".into(),
            payload: "{}".into(),
            ..Default::default()
        })));
        db.write(&epoch, writes).await.unwrap();
        for kind in 0..3 {
            let mut forward = Vec::new();
            let mut backward = Vec::new();
            for offset in [0, 100, 200, 300] {
                for descending in [false, true] {
                    let page = records(&db, queries(offset, descending)[kind].clone()).await;
                    let expected = usize::try_from((235_i64 - offset).clamp(0, 100)).unwrap();
                    assert_eq!(page.records.len(), expected);
                    assert_eq!(page.has_more, offset < 200);
                    if kind == 2 {
                        assert_eq!(page.users.len(), page.records.len());
                        assert!(
                            page.records
                                .iter()
                                .all(|r| matches!(r,Record::UserHistory(h) if h.account=="a"))
                        );
                    }
                    let ids = page.records.iter().map(record_id);
                    if descending {
                        backward.extend(ids);
                    } else {
                        forward.extend(ids);
                    }
                }
            }
            assert_eq!(forward.len(), 235);
            assert_eq!(forward.iter().collect::<BTreeSet<_>>().len(), 235);
            backward.reverse();
            assert_eq!(forward, backward);
        }
        for (offset, limit) in [(-1, 10), (0, 0), (0, 1001)] {
            assert!(
                db.query(Query::SearchUsers {
                    text: String::new(),
                    range: Range {
                        offset,
                        limit,
                        descending: false
                    }
                })
                .await
                .is_err()
            );
        }
        let query = Query::UserHistory {
            account: Some("a".into()),
            range: range(100, false),
        };
        let mut stream = db.observe(query.clone()).await.unwrap();
        stream.next().await.unwrap().unwrap();
        db.write(
            &epoch,
            vec![Mutation::Put(Record::UserHistory(UserHistory {
                account: "a".into(),
                user_key: "234".into(),
                last_visit: 0,
            }))],
        )
        .await
        .unwrap();
        assert_eq!(
            *stream.next().await.unwrap().unwrap(),
            db.query(query).await.unwrap()
        );
    });
}

#[test]
fn translation_reads_keep_entity_types_languages_and_duplicate_keys_isolated() {
    block_on(async {
        let (_directory, db, epoch) = fixture().await;
        let id = key(&["a", "s"]);
        db.write(&epoch, vec![Mutation::Put(Record::Status(status()))])
            .await
            .unwrap();
        let mut writes = Vec::new();
        for entity_type in ["Status", "Profile"] {
            for language in ["en", "zh"] {
                writes.push(Mutation::Put(Record::Translation(Translation {
                    entity_type: entity_type.into(),
                    entity_key: id.clone(),
                    target_language: language.into(),
                    source_hash: format!("{entity_type}-{language}"),
                    status: "Completed".into(),
                    display_mode: "Auto".into(),
                    payload: Some("{}".into()),
                    ..Default::default()
                })));
            }
        }
        db.write(&epoch, writes).await.unwrap();
        let Snapshot::StatusBundle(bundle) =
            db.query(Query::Statuses(vec![id.clone()])).await.unwrap()
        else {
            panic!()
        };
        assert_eq!(bundle.translations.len(), 2);
        assert!(
            bundle
                .translations
                .iter()
                .all(|t| t.entity_type == "Status")
        );
        let query = Query::Translations {
            entity_type: "Profile".into(),
            entity_keys: vec![id.clone(), id.clone()],
            language: Some("zh".into()),
        };
        let Snapshot::Records(rows) = db.query(query.clone()).await.unwrap() else {
            panic!()
        };
        assert_eq!(rows.len(), 1);
        assert!(matches!(&rows[0],Record::Translation(t) if t.source_hash=="Profile-zh"));
        let mut stream = db.observe(query.clone()).await.unwrap();
        stream.next().await.unwrap().unwrap();
        db.write(
            &epoch,
            vec![Mutation::TranslationDisplay {
                id: key(&["Status", &id, "zh"]),
                display_mode: "Translated".into(),
                updated_at: 2,
            }],
        )
        .await
        .unwrap();
        assert!(stream.next().now_or_never().is_none());
        db.write(
            &epoch,
            vec![Mutation::TranslationDisplay {
                id: key(&["Profile", &id, "zh"]),
                display_mode: "Translated".into(),
                updated_at: 2,
            }],
        )
        .await
        .unwrap();
        assert_eq!(
            stream
                .next()
                .now_or_never()
                .unwrap()
                .unwrap()
                .unwrap()
                .as_ref(),
            &db.query(query).await.unwrap()
        );
    });
}

#[test]
fn status_cas_rejects_stale_deleted_and_recreated_rows_atomically() {
    block_on(async {
        let (_directory, db, epoch) = fixture().await;
        db.write(&epoch, vec![Mutation::Put(Record::Status(status()))])
            .await
            .unwrap();
        let original = read_status(&db).await;
        assert_eq!(original.version.len(), 32);
        let mut stream = db
            .observe(Query::Status(original.id.clone()))
            .await
            .unwrap();
        stream.next().await.unwrap().unwrap();
        db.write(&epoch, vec![Mutation::Put(Record::Status(status()))])
            .await
            .unwrap();
        assert_eq!(read_status(&db).await.version, original.version);
        assert!(stream.next().now_or_never().is_none());
        let mut changed = original.clone();
        changed.payload = "{\"likes\":1,\"bookmarks\":0}".into();
        let edit = Mutation::UpdateStatus {
            expected_version: original.version.clone(),
            status: changed,
        };
        db.write(&epoch, vec![edit.clone()]).await.unwrap();
        stream.next().await.unwrap().unwrap();
        assert_ne!(read_status(&db).await.version, original.version);
        assert_eq!(
            db.write(
                &epoch,
                vec![
                    Mutation::Put(Record::User(User {
                        user_key: "rollback".into(),
                        payload: "{}".into(),
                        ..Default::default()
                    })),
                    edit.clone()
                ]
            )
            .await
            .unwrap_err(),
            Error::Conflict
        );
        assert!(stream.next().now_or_never().is_none());
        assert_eq!(
            db.query(Query::Users(vec!["rollback".into()]))
                .await
                .unwrap(),
            Snapshot::Records(vec![])
        );
        db.write(
            &epoch,
            vec![Mutation::Delete(flare_database::cache::Delete::Status(
                original.id.clone(),
            ))],
        )
        .await
        .unwrap();
        stream.next().await.unwrap().unwrap();
        assert_eq!(
            db.write(&epoch, vec![edit.clone()]).await.unwrap_err(),
            Error::Conflict
        );
        assert_eq!(
            db.update_status(&epoch, &original.id, |_| panic!(
                "missing status must not be edited"
            ))
            .await
            .unwrap_err(),
            Error::Conflict
        );
        db.write(&epoch, vec![Mutation::Put(Record::Status(status()))])
            .await
            .unwrap();
        assert_ne!(read_status(&db).await.version, original.version);
        assert_eq!(
            db.write(&epoch, vec![edit]).await.unwrap_err(),
            Error::Conflict
        );
    });
}

#[test]
fn status_retry_rebases_concurrent_edits_and_respects_clear() {
    let (_directory, db, epoch) = block_on(fixture());
    block_on(db.write(&epoch, vec![Mutation::Put(Record::Status(status()))])).unwrap();
    let barrier = Arc::new(Barrier::new(2));
    let calls = Arc::new(AtomicUsize::new(0));
    let id = key(&["a", "s"]);
    std::thread::scope(|scope| {
        let threads: Vec<_> = ["likes", "bookmarks"]
            .into_iter()
            .map(|field| {
                let db = &db;
                let epoch = &epoch;
                let id = &id;
                let barrier = Arc::clone(&barrier);
                let calls = Arc::clone(&calls);
                scope.spawn(move || {
                    let invocation = AtomicUsize::new(0);
                    block_on(db.update_status(epoch, id, |row| {
                        calls.fetch_add(1, Ordering::SeqCst);
                        if invocation.fetch_add(1, Ordering::SeqCst) == 0 {
                            barrier.wait();
                        }
                        let mut json: serde_json::Value =
                            serde_json::from_str(&row.payload).unwrap();
                        json[field] = 1.into();
                        row.payload = json.to_string();
                        Ok(())
                    }))
                    .unwrap();
                })
            })
            .collect();
        for thread in threads {
            thread.join().unwrap();
        }
    });
    assert_eq!(calls.load(Ordering::SeqCst), 3);
    let row = block_on(read_status(&db));
    let payload: serde_json::Value = serde_json::from_str(&row.payload).unwrap();
    assert_eq!(payload["likes"], 1);
    assert_eq!(payload["bookmarks"], 1);
    assert!(matches!(
        block_on(db.update_status(&epoch, &id, |r| {
            r.account = "other".into();
            Ok(())
        })),
        Err(Error::InvalidRequest(_))
    ));
    block_on(db.write(&epoch, vec![Mutation::ResetCache])).unwrap();
    assert_eq!(
        block_on(db.update_status(&epoch, &id, |_| panic!("old epoch must not run an edit")))
            .unwrap_err(),
        Error::Conflict
    );
}

fn translation(id: &str, status: &str, at: i64) -> Translation {
    Translation {
        id: key(&["Status", id, "zh"]),
        entity_type: "Status".into(),
        entity_key: id.into(),
        target_language: "zh".into(),
        source_hash: "source".into(),
        status: status.into(),
        display_mode: "Auto".into(),
        payload: Some("{\"previous\":true}".into()),
        status_reason: Some("previous".into()),
        attempt_count: 1,
        updated_at: at,
    }
}
async fn translations(db: &CacheDatabase, ids: Vec<String>) -> Vec<Translation> {
    let Snapshot::Records(rows) = db
        .query(Query::Translations {
            entity_type: "Status".into(),
            entity_keys: ids,
            language: Some("zh".into()),
        })
        .await
        .unwrap()
    else {
        panic!()
    };
    rows.into_iter()
        .map(|r| {
            if let Record::Translation(t) = r {
                t
            } else {
                panic!()
            }
        })
        .collect()
}

#[test]
fn translation_start_is_an_exclusive_conditional_transition() {
    block_on(async {
        let (_directory, db, epoch) = fixture().await;
        let pending = translation("s", "Pending", 1);
        db.write(
            &epoch,
            vec![Mutation::Put(Record::Translation(pending.clone()))],
        )
        .await
        .unwrap();
        let start = |source: &str, attempt| Mutation::StartTranslation {
            id: pending.id.clone(),
            source_hash: source.into(),
            attempt_count: attempt,
            updated_at: 2,
        };
        for (source, attempt) in [("stale", 1), ("source", 0)] {
            assert_eq!(
                db.write(&epoch, vec![start(source, attempt)])
                    .await
                    .unwrap_err(),
                Error::Conflict
            );
        }
        let results =
            futures::future::join_all((0..2).map(|_| db.write(&epoch, vec![start("source", 1)])))
                .await;
        assert_eq!(results.iter().filter(|r| r.is_ok()).count(), 1);
        let current = translations(&db, vec!["s".into()]).await.remove(0);
        assert_eq!(current.status, "Translating");
        assert!(current.payload.is_none());
        assert!(current.status_reason.is_none());
        db.write(
            &epoch,
            vec![Mutation::TranslationResult(TranslationResult {
                id: pending.id.clone(),
                source_hash: "source".into(),
                attempt_count: 1,
                status: "Completed".into(),
                payload: Some("{}".into()),
                reason: None,
                updated_at: 3,
            })],
        )
        .await
        .unwrap();
        assert_eq!(
            db.write(&epoch, vec![start("source", 1)])
                .await
                .unwrap_err(),
            Error::Conflict
        );
    });
}

#[test]
fn stale_translation_failure_clears_payload_and_respects_cutoff_and_terminal_states() {
    block_on(async {
        let (_directory, db, epoch) = fixture().await;
        let rows = [
            translation("pending", "Pending", 1),
            translation("active", "Translating", 1),
            translation("cutoff", "Pending", 3),
            translation("completed", "Completed", 1),
            translation("failed", "Failed", 1),
        ];
        db.write(
            &epoch,
            rows.iter()
                .cloned()
                .map(|r| Mutation::Put(Record::Translation(r)))
                .collect(),
        )
        .await
        .unwrap();
        db.write(
            &epoch,
            vec![Mutation::FailStaleTranslations {
                before: 3,
                reason: "interrupted".into(),
                updated_at: 4,
            }],
        )
        .await
        .unwrap();
        for row in translations(&db, rows.iter().map(|r| r.entity_key.clone()).collect()).await {
            if ["pending", "active"].contains(&row.entity_key.as_str()) {
                assert_eq!(row.status, "Failed");
                assert!(row.payload.is_none());
                assert_eq!(row.status_reason.as_deref(), Some("interrupted"));
                assert_eq!(row.updated_at, 4);
            } else {
                assert_eq!(&row, rows.iter().find(|r| r.id == row.id).unwrap());
            }
        }
    });
}
