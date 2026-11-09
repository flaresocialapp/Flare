#![cfg(not(target_arch = "wasm32"))]
use diesel::{Connection, connection::SimpleConnection};
use flare_database::{AgentDatabase, AppDatabase, AppQuery, CacheDatabase, Error, cache::*};
use futures::{FutureExt, StreamExt, executor::block_on};

async fn epoch(db: &CacheDatabase) -> String {
    let Snapshot::Info(i) = db.query(Query::Info).await.unwrap() else {
        panic!()
    };
    i.epoch
}
fn status(account: &str, id: &str) -> Status {
    Status {
        id: key(&[account, id]),
        account: account.into(),
        status_key: id.into(),
        payload: format!("{{\"id\":\"{id}\",\"large\":9007199254740993}}"),
        text: Some("search 100%_\\ literal".into()),
        ..Default::default()
    }
}
fn row(pkey: &str, id: &str, order: i64) -> TimelineRow {
    TimelineRow {
        paging_key: pkey.into(),
        timeline_id: id.into(),
        status_id: key(&["a", id]),
        sort_id: order,
        ..Default::default()
    }
}
fn range() -> Range {
    Range {
        offset: 0,
        limit: 100,
        descending: false,
    }
}
async fn reset(db: &CacheDatabase, cache_epoch: &str, pkey: &str) -> PagingKey {
    db.write(
        cache_epoch,
        vec![Mutation::ResetTimeline {
            paging_key: pkey.into(),
            account_keys: vec!["a".into()],
        }],
    )
    .await
    .unwrap()
    .pages
    .remove(0)
}
fn page(p: &PagingKey, statuses: Vec<Status>, items: Vec<TimelineRow>) -> PageWrite {
    PageWrite {
        paging_key: p.paging_key.clone(),
        expected: PageGuard {
            generation: p.generation.clone(),
            revision: p.revision,
        },
        statuses,
        references: vec![],
        items,
        presentations: vec![],
        remove_ids: vec![],
        next_key: Some("opaque-next".into()),
        keep_next_key: false,
        prev_key: None,
        source_heads: "{\"future\":9007199254740993}".into(),
        account_keys: vec!["a".into()],
    }
}
async fn read_page(db: &CacheDatabase, pkey: &str) -> Page {
    let Snapshot::Page(p) = db
        .query(Query::TimelinePage {
            paging_key: pkey.into(),
            range: range(),
        })
        .await
        .unwrap()
    else {
        panic!()
    };
    p
}
async fn stats(db: &CacheDatabase) -> Vec<(String, i64)> {
    let Snapshot::Stats(s) = db.query(Query::Stats).await.unwrap() else {
        panic!()
    };
    s
}
fn translation(id: &str) -> Translation {
    Translation {
        id: key(&["Status", id, "zh"]),
        entity_type: "Status".into(),
        entity_key: id.into(),
        target_language: "zh".into(),
        source_hash: "source-1".into(),
        status: "Translating".into(),
        display_mode: "Auto".into(),
        attempt_count: 1,
        ..Default::default()
    }
}
fn message(id: &str, local: bool) -> Message {
    Message {
        message_key: id.into(),
        room_key: "r".into(),
        user_key: "u".into(),
        timestamp: 9_007_199_254_740_993,
        payload: "{\"state\":\"Sending\"}".into(),
        is_local: local,
        ..Default::default()
    }
}

#[test]
fn takeover_rebuilds_once_and_preserves_other_databases() {
    block_on(async {
        for version in [1, 52, 53] {
            let dir = tempfile::tempdir().unwrap();
            let path = dir.path().join("cache.db");
            let app = AppDatabase::open(dir.path().join("app.db")).await.unwrap();
            let agent = AgentDatabase::open(dir.path().join("agent.db"))
                .await
                .unwrap();
            std::fs::write(dir.path().join("settings.pb"), b"keep").unwrap();
            let mut c = diesel::SqliteConnection::establish(path.to_str().unwrap()).unwrap();
            c.batch_execute(&format!("CREATE TABLE DbStatus(id TEXT);CREATE TABLE DbPagingTimeline(id TEXT);CREATE TABLE DbUser(id TEXT);CREATE TABLE room_master_table(id INTEGER);INSERT INTO DbStatus VALUES('old');PRAGMA user_version={version}")).unwrap();
            drop(c);
            let db = CacheDatabase::open(&path).await.unwrap();
            assert!(stats(&db).await.iter().all(|(_, n)| *n == 0));
            let cache_epoch = epoch(&db).await;
            db.write(
                &cache_epoch,
                vec![Mutation::Put(Record::Status(status("a", "new")))],
            )
            .await
            .unwrap();
            drop(db);
            let reopened = CacheDatabase::open(&path).await.unwrap();
            assert_eq!(epoch(&reopened).await, cache_epoch);
            assert_eq!(stats(&reopened).await[0].1, 1);
            let cleared = reopened
                .write(&cache_epoch, vec![Mutation::ResetCache])
                .await
                .unwrap();
            assert_ne!(cleared.epoch, cache_epoch);
            assert!(stats(&reopened).await.iter().all(|(_, n)| *n == 0));
            assert!(matches!(
                reopened
                    .write(
                        &cache_epoch,
                        vec![Mutation::Put(Record::Status(status("a", "late")))]
                    )
                    .await,
                Err(Error::Conflict)
            ));
            app.query(AppQuery::Accounts).await.unwrap();
            agent
                .query(flare_database::agent::Query::Conversations)
                .await
                .unwrap();
            assert_eq!(
                std::fs::read(dir.path().join("settings.pb")).unwrap(),
                b"keep"
            );
            assert!(
                CacheDatabase::open(dir.path().join("app.db"))
                    .await
                    .is_err()
            );
            assert!(
                CacheDatabase::open(dir.path().join("agent.db"))
                    .await
                    .is_err()
            );
            assert!(AppDatabase::open(&path).await.is_err());
        }
    });
}
#[test]
fn unknown_future_corrupt_and_tampered_files_are_preserved() {
    for sql in [
        "PRAGMA user_version=54",
        "PRAGMA application_id=0x43414348; PRAGMA user_version=5",
        "CREATE TABLE unrelated(x TEXT)",
        "CREATE VIEW unknown AS SELECT 1",
    ] {
        let directory = tempfile::tempdir().unwrap();
        let p = directory.path().join("cache.db");
        let mut c = diesel::SqliteConnection::establish(p.to_str().unwrap()).unwrap();
        c.batch_execute(sql).unwrap();
        drop(c);
        let before = std::fs::read(&p).unwrap();
        assert!(block_on(CacheDatabase::open(&p)).is_err());
        assert_eq!(std::fs::read(&p).unwrap(), before);
    }
    let directory = tempfile::tempdir().unwrap();
    let p = directory.path().join("cache.db");
    std::fs::write(&p, b"corrupt").unwrap();
    assert!(block_on(CacheDatabase::open(&p)).is_err());
    assert_eq!(std::fs::read(&p).unwrap(), b"corrupt");
}

#[test]
#[allow(
    clippy::too_many_lines,
    reason = "One atomic paging, rollback and reset scenario"
)]
fn guarded_pages_are_atomic_gap_aware_and_reset_fenced() {
    block_on(async {
        let directory = tempfile::tempdir().unwrap();
        let db = CacheDatabase::open(directory.path().join("cache.db"))
            .await
            .unwrap();
        let cache_epoch = epoch(&db).await;
        let p = reset(&db, &cache_epoch, "home").await;
        let mut a = row("home", "a", i64::MIN);
        a.is_gap = true;
        let mut b = row("home", "b", i64::MAX);
        b.message = Some("{\"notice\":true}".into());
        b.message_render_hash = Some(i64::MIN);
        let mut write = page(
            &p,
            vec![status("a", "a"), status("a", "b")],
            vec![b.clone(), a.clone()],
        );
        write.references = vec![ReferenceSet {
            status_id: a.status_id.clone(),
            references: vec![Reference {
                status_id: a.status_id.clone(),
                reference_type: "Quote".into(),
                reference_status_id: "missing-soft-target".into(),
                reference_order: 0,
            }],
        }];
        let committed = db
            .write(&cache_epoch, vec![Mutation::CommitPage(write.clone())])
            .await
            .unwrap();
        assert!(committed.pages[0].revision > p.revision);
        let result = read_page(&db, "home").await;
        assert_eq!(result.rows[0].timeline_id, "a");
        assert!(result.rows[0].is_gap);
        assert_eq!(
            result.references[0].reference_status_id,
            "missing-soft-target"
        );
        assert_eq!(
            result.metadata.as_ref().unwrap().source_heads,
            "{\"future\":9007199254740993}"
        );
        assert_eq!(
            db.query(Query::SizeThrough {
                paging_key: "home".into(),
                timeline_id: "b".into()
            })
            .await
            .unwrap(),
            Snapshot::Count(2)
        );
        let Snapshot::Records(next) = db
            .query(Query::NextRow {
                paging_key: "home".into(),
                timeline_id: "a".into(),
            })
            .await
            .unwrap()
        else {
            panic!()
        };
        assert!(matches!(&next[0],Record::Timeline(r) if r.timeline_id=="b"));
        assert_eq!(
            db.write(&cache_epoch, vec![Mutation::CommitPage(write)])
                .await
                .unwrap_err(),
            Error::Conflict
        );
        let mut invalid = page(
            &committed.pages[0],
            vec![status("a", "rollback")],
            vec![row("other", "rollback", 0)],
        );
        assert!(
            db.write(&cache_epoch, vec![Mutation::CommitPage(invalid.clone())])
                .await
                .is_err()
        );
        assert_eq!(stats(&db).await[0].1, 2);
        invalid.items = vec![];
        invalid.keep_next_key = true;
        invalid.next_key = None;
        let last = db
            .write(&cache_epoch, vec![Mutation::CommitPage(invalid)])
            .await
            .unwrap()
            .pages
            .remove(0);
        assert_eq!(last.next_key.as_deref(), Some("opaque-next"));
        let newer = reset(&db, &cache_epoch, "home").await;
        assert_ne!(newer.generation, last.generation);
        assert!(matches!(
            db.write(
                &cache_epoch,
                vec![Mutation::CommitPage(page(&last, vec![], vec![]))]
            )
            .await,
            Err(Error::Conflict)
        ));
        assert!(read_page(&db, "home").await.rows.is_empty());
    });
}

#[test]
#[allow(
    clippy::too_many_lines,
    reason = "One graph propagation and translation CAS scenario"
)]
fn graph_and_translation_changes_invalidate_only_affected_timelines() {
    block_on(async {
        let directory = tempfile::tempdir().unwrap();
        let db = CacheDatabase::open(directory.path().join("cache.db"))
            .await
            .unwrap();
        let cache_epoch = epoch(&db).await;
        let p = reset(&db, &cache_epoch, "home").await;
        let q = reset(&db, &cache_epoch, "other").await;
        let root = status("a", "root");
        let parent = status("a", "parent");
        let target = status("a", "target");
        let mut w = page(
            &p,
            vec![root.clone(), parent.clone(), target.clone()],
            vec![row("home", "root", 1)],
        );
        w.presentations = vec![PresentationSet {
            timeline_id: "root".into(),
            references: vec![PresentationReference {
                paging_key: "home".into(),
                timeline_id: "root".into(),
                presentation_type: "InlineParent".into(),
                reference_status_id: parent.id.clone(),
                reference_order: 0,
            }],
        }];
        w.references = vec![ReferenceSet {
            status_id: parent.id.clone(),
            references: vec![Reference {
                status_id: parent.id.clone(),
                reference_type: "Quote".into(),
                reference_status_id: target.id.clone(),
                reference_order: 0,
            }],
        }];
        db.write(
            &cache_epoch,
            vec![
                Mutation::CommitPage(w),
                Mutation::CommitPage(page(
                    &q,
                    vec![status("a", "unrelated")],
                    vec![row("other", "unrelated", 1)],
                )),
            ],
        )
        .await
        .unwrap();
        let mut home = db
            .observe(Query::TimelineIdentities {
                paging_key: "home".into(),
                range: range(),
            })
            .await
            .unwrap();
        let first = home.next().await.unwrap().unwrap();
        let mut other = db
            .observe(Query::TimelinePage {
                paging_key: "other".into(),
                range: range(),
            })
            .await
            .unwrap();
        other.next().await.unwrap().unwrap();
        let mut changed = target.clone();
        changed.payload = "{\"changed\":true}".into();
        db.write(
            &cache_epoch,
            vec![Mutation::Put(Record::Status(changed.clone()))],
        )
        .await
        .unwrap();
        let after = home.next().await.unwrap().unwrap();
        let Snapshot::Identities(before) = first.as_ref() else {
            panic!()
        };
        let Snapshot::Identities(after) = after.as_ref() else {
            panic!()
        };
        assert!(after.rows[0].content_revision > before.rows[0].content_revision);
        assert!(other.next().now_or_never().is_none());
        assert_eq!(
            after.metadata.as_ref().unwrap().revision,
            before.metadata.as_ref().unwrap().revision,
            "content does not stale a structural page guard"
        );
        db.write(&cache_epoch, vec![Mutation::Put(Record::Status(changed))])
            .await
            .unwrap();
        assert!(home.next().now_or_never().is_none());
        let translation = translation(&target.id);
        db.write(
            &cache_epoch,
            vec![Mutation::Put(Record::Translation(translation.clone()))],
        )
        .await
        .unwrap();
        home.next().await.unwrap().unwrap();
        db.write(
            &cache_epoch,
            vec![Mutation::TranslationDisplay {
                id: translation.id.clone(),
                display_mode: "Auto".into(),
                updated_at: 5,
            }],
        )
        .await
        .unwrap();
        assert!(
            home.next().now_or_never().is_none(),
            "timestamp-only updates do not render a page"
        );
        let mut profile = translation.clone();
        profile.id = String::new();
        profile.entity_type = "Profile".into();
        db.write(
            &cache_epoch,
            vec![Mutation::Put(Record::Translation(profile))],
        )
        .await
        .unwrap();
        assert!(
            home.next().now_or_never().is_none(),
            "profile translations are not status translations"
        );
        let result = |hash: &str, attempt: i64| {
            Mutation::TranslationResult(TranslationResult {
                id: translation.id.clone(),
                source_hash: hash.into(),
                attempt_count: attempt,
                status: "Completed".into(),
                payload: Some("{\"translated\":true}".into()),
                reason: None,
                updated_at: 4,
            })
        };
        assert!(matches!(
            db.write(&cache_epoch, vec![result("stale", 1)]).await,
            Err(Error::Conflict)
        ));
        assert!(home.next().now_or_never().is_none());
        db.write(&cache_epoch, vec![result("source-1", 1)])
            .await
            .unwrap();
        home.next().await.unwrap().unwrap();
        assert!(matches!(
            db.write(&cache_epoch, vec![result("source-1", 1)]).await,
            Err(Error::Conflict)
        ));
        db.write(
            &cache_epoch,
            vec![Mutation::Delete(Delete::References(parent.id.clone()))],
        )
        .await
        .unwrap();
        home.next().await.unwrap().unwrap();
        let hydrated = read_page(&db, "home").await;
        assert!(hydrated.references.is_empty());
        assert_eq!(hydrated.statuses.len(), 2);
        assert!(other.next().now_or_never().is_none());
    });
}

#[test]
fn root_semantic_and_presentation_dependencies_follow_soft_target_changes() {
    block_on(async {
        let directory = tempfile::tempdir().unwrap();
        let db = CacheDatabase::open(directory.path().join("cache.db"))
            .await
            .unwrap();
        let cache_epoch = epoch(&db).await;
        let target = status("a", "target");
        db.write(
            &cache_epoch,
            vec![Mutation::Put(Record::Status(target.clone()))],
        )
        .await
        .unwrap();
        let mut before = Vec::new();
        for scope in ["root", "semantic", "presentation"] {
            let metadata = reset(&db, &cache_epoch, scope).await;
            let mut timeline = row(scope, scope, 1);
            if scope == "root" {
                timeline.status_id = target.id.clone();
            }
            let mut write = page(
                &metadata,
                if scope == "root" {
                    vec![]
                } else {
                    vec![status("a", scope)]
                },
                vec![timeline.clone()],
            );
            if scope == "semantic" {
                write.references.push(ReferenceSet {
                    status_id: timeline.status_id.clone(),
                    references: vec![Reference {
                        status_id: timeline.status_id,
                        reference_type: "Quote".into(),
                        reference_status_id: target.id.clone(),
                        reference_order: 0,
                    }],
                });
            }
            if scope == "presentation" {
                write.presentations.push(PresentationSet {
                    timeline_id: scope.into(),
                    references: vec![PresentationReference {
                        paging_key: scope.into(),
                        timeline_id: scope.into(),
                        presentation_type: "Quote".into(),
                        reference_status_id: target.id.clone(),
                        reference_order: 0,
                    }],
                });
            }
            db.write(&cache_epoch, vec![Mutation::CommitPage(write)])
                .await
                .unwrap();
            before.push((scope, read_page(&db, scope).await));
        }
        let mut changed = target.clone();
        changed.payload = "{\"changed\":true}".into();
        db.write(&cache_epoch, vec![Mutation::Put(Record::Status(changed))])
            .await
            .unwrap();
        for (scope, initial) in &before {
            let page = read_page(&db, scope).await;
            assert!(page.rows[0].content_revision > initial.rows[0].content_revision);
            assert_eq!(
                page.metadata.as_ref().unwrap().revision,
                initial.metadata.as_ref().unwrap().revision
            );
        }
        db.write(
            &cache_epoch,
            vec![Mutation::Delete(Delete::Status(target.id.clone()))],
        )
        .await
        .unwrap();
        assert!(read_page(&db, "root").await.rows.is_empty());
        for scope in ["semantic", "presentation"] {
            let page = read_page(&db, scope).await;
            assert_eq!(page.rows.len(), 1);
            assert_eq!(page.statuses.len(), 1);
        }
        db.write(&cache_epoch, vec![Mutation::Put(Record::Status(target))])
            .await
            .unwrap();
        for scope in ["semantic", "presentation"] {
            assert_eq!(read_page(&db, scope).await.statuses.len(), 2);
        }
    });
}

#[test]
#[allow(
    clippy::too_many_lines,
    reason = "Fixture exercises every cache entity and clear coverage"
)]
fn all_entities_roundtrip_search_is_literal_and_clear_covers_histories_and_local_messages() {
    block_on(async {
        let directory = tempfile::tempdir().unwrap();
        let db = CacheDatabase::open(directory.path().join("cache.db"))
            .await
            .unwrap();
        let cache_epoch = epoch(&db).await;
        let p = reset(&db, &cache_epoch, "home").await;
        let mut w = page(&p, vec![status("a", "s")], vec![row("home", "s", 1)]);
        w.references = vec![ReferenceSet {
            status_id: key(&["a", "s"]),
            references: vec![Reference {
                status_id: key(&["a", "s"]),
                reference_type: "Quote".into(),
                reference_status_id: "missing".into(),
                reference_order: 0,
            }],
        }];
        w.presentations = vec![PresentationSet {
            timeline_id: "s".into(),
            references: vec![PresentationReference {
                paging_key: "home".into(),
                timeline_id: "s".into(),
                presentation_type: "InlineParent".into(),
                reference_status_id: "parent".into(),
                reference_order: 0,
            }],
        }];
        let records = vec![
            Record::User(User {
                user_key: "u".into(),
                name: "100%_\\ literal".into(),
                canonical_handle: "u@host".into(),
                host: "host".into(),
                payload: "{}".into(),
            }),
            Record::Relation(Relation {
                account: "a".into(),
                user_key: "u".into(),
                payload: "{}".into(),
            }),
            Record::UserHistory(UserHistory {
                account: "a".into(),
                user_key: "u".into(),
                last_visit: i64::MAX,
            }),
            Record::Emoji(Emoji {
                host: "host".into(),
                payload: "[]".into(),
            }),
            Record::EmojiHistory(EmojiHistory {
                account: "a".into(),
                short_code: "smile".into(),
                last_use: 2,
            }),
            Record::List(List {
                id: String::new(),
                account: "a".into(),
                list_key: "list".into(),
                payload: "{}".into(),
            }),
            Record::ListPage(ListPage {
                paging_key: "lists".into(),
                account: "a".into(),
                list_key: "list".into(),
                position: 1,
            }),
            Record::ListMember(ListMember {
                list_key: "list".into(),
                member_key: "u".into(),
            }),
            Record::RoomTimeline(RoomTimeline {
                account: "a".into(),
                room_key: "r".into(),
                sort_id: 0,
                unread_count: 3,
                payload: "{}".into(),
            }),
            Record::Room(Room {
                room_key: "r".into(),
                platform_id: "r".into(),
                last_message_key: None,
            }),
            Record::RoomMember(RoomMember {
                room_key: "r".into(),
                user_key: "u".into(),
            }),
            Record::Message(message("local", true)),
            Record::Translation(translation(&key(&["a", "s"]))),
        ];
        let mut mutations = vec![Mutation::CommitPage(w)];
        mutations.extend(records.into_iter().map(Mutation::Put));
        db.write(&cache_epoch, mutations).await.unwrap();
        assert!(stats(&db).await.iter().all(|(_, n)| *n == 1));
        for query in [
            Query::UserByHandle {
                canonical_handle: "u@host".into(),
                host: "host".into(),
            },
            Query::Relation {
                account: "a".into(),
                user_key: "u".into(),
            },
            Query::Emoji("host".into()),
            Query::Lists {
                paging_key: "lists".into(),
                range: range(),
            },
            Query::ListsForUser {
                account: "a".into(),
                user_key: "u".into(),
            },
        ] {
            let Snapshot::Records(r) = db.query(query).await.unwrap() else {
                panic!()
            };
            assert_eq!(r.len(), 1);
        }
        for query in [
            Query::Rooms {
                account: "a".into(),
                range: range(),
            },
            Query::RoomMessages {
                room_key: "r".into(),
                range: range(),
            },
        ] {
            let Snapshot::Joined(j) = db.query(query).await.unwrap() else {
                panic!()
            };
            assert_eq!(j.users.len(), 1);
        }
        for query in [
            Query::SearchStatuses {
                text: "%_\\".into(),
                range: range(),
            },
            Query::SearchUsers {
                text: "%_\\".into(),
                range: range(),
            },
            Query::UserHistory {
                account: Some("a".into()),
                range: range(),
            },
            Query::ListMembers {
                list_key: "list".into(),
                range: range(),
            },
            Query::EmojiHistory {
                account: "a".into(),
                range: range(),
            },
        ] {
            match db.query(query).await.unwrap() {
                Snapshot::RecordsPage(page) => {
                    assert_eq!(page.records.len(), 1);
                    assert!(!page.has_more);
                }
                Snapshot::StatusPage(page) => {
                    assert_eq!(page.root_ids.len(), 1);
                    assert!(!page.has_more);
                    assert_eq!(page.bundle.references.len(), 1);
                    assert_eq!(page.bundle.translations.len(), 1);
                }
                _ => panic!(),
            }
        }
        let Snapshot::Records(remote) = db
            .query(Query::LatestRemoteMessage("r".into()))
            .await
            .unwrap()
        else {
            panic!()
        };
        assert!(remote.is_empty());
        db.write(&cache_epoch, vec![Mutation::ResetCache])
            .await
            .unwrap();
        assert!(stats(&db).await.iter().all(|(_, n)| *n == 0));
        assert_ne!(epoch(&db).await, cache_epoch);
    });
}

#[test]
fn local_message_ack_is_atomic_idempotent_and_scoped() {
    block_on(async {
        let directory = tempfile::tempdir().unwrap();
        let db = CacheDatabase::open(directory.path().join("cache.db"))
            .await
            .unwrap();
        let cache_epoch = epoch(&db).await;
        db.write(
            &cache_epoch,
            vec![
                Mutation::Put(Record::RoomTimeline(RoomTimeline {
                    account: "a".into(),
                    room_key: "r".into(),
                    sort_id: i64::MAX,
                    unread_count: 3,
                    payload: "{}".into(),
                })),
                Mutation::Put(Record::Message(message("local", true))),
            ],
        )
        .await
        .unwrap();
        db.write(
            &cache_epoch,
            vec![Mutation::UpdateLocalMessage {
                message_key: "local".into(),
                expected_revision: 0,
                payload: "{\"state\":\"Failed\"}".into(),
            }],
        )
        .await
        .unwrap();
        let ack = |revision| Mutation::AcknowledgeMessage {
            local_key: "local".into(),
            expected_revision: revision,
            remote: message("remote", false),
        };
        assert!(matches!(
            db.write(&cache_epoch, vec![ack(0)]).await,
            Err(Error::Conflict)
        ));
        db.write(&cache_epoch, vec![ack(1)]).await.unwrap();
        db.write(&cache_epoch, vec![ack(1)]).await.unwrap();
        let Snapshot::Joined(j) = db
            .query(Query::RoomMessages {
                room_key: "r".into(),
                range: range(),
            })
            .await
            .unwrap()
        else {
            panic!()
        };
        assert_eq!(j.messages.len(), 1);
        assert!(!j.messages[0].is_local);
        assert_eq!(j.messages[0].message_key, "remote");
        let Snapshot::Joined(room) = db
            .query(Query::Room {
                account: "a".into(),
                room_key: "r".into(),
            })
            .await
            .unwrap()
        else {
            panic!()
        };
        assert!(
            matches!(&room.records[0],Record::RoomTimeline(t) if t.sort_id==9_007_199_254_740_993)
        );
    });
}

#[test]
fn account_clear_does_not_sweep_unassociated_shared_rows() {
    block_on(async {
        let directory = tempfile::tempdir().unwrap();
        let db = CacheDatabase::open(directory.path().join("cache.db"))
            .await
            .unwrap();
        let cache_epoch = epoch(&db).await;
        db.write(
            &cache_epoch,
            vec![
                Mutation::Put(Record::Message(message(
                    "not-in-an-account-timeline",
                    false,
                ))),
                Mutation::Put(Record::ListMember(ListMember {
                    list_key: "prefetched".into(),
                    member_key: "u".into(),
                })),
            ],
        )
        .await
        .unwrap();
        db.write(
            &cache_epoch,
            vec![Mutation::ClearAccount("unrelated-account".into())],
        )
        .await
        .unwrap();
        let counts: std::collections::BTreeMap<_, _> = stats(&db).await.into_iter().collect();
        assert_eq!(counts["cache_messages"], 1);
        assert_eq!(counts["cache_list_members"], 1);
    });
}

#[test]
fn account_clear_keeps_shared_rooms_lists_and_users() {
    block_on(async {
        let directory = tempfile::tempdir().unwrap();
        let db = CacheDatabase::open(directory.path().join("cache.db"))
            .await
            .unwrap();
        let cache_epoch = epoch(&db).await;
        let mut writes = Vec::new();
        for account in ["a", "b"] {
            writes.extend([
                Mutation::Put(Record::RoomTimeline(RoomTimeline {
                    account: account.into(),
                    room_key: "r".into(),
                    payload: "{}".into(),
                    ..Default::default()
                })),
                Mutation::Put(Record::List(List {
                    id: String::new(),
                    account: account.into(),
                    list_key: "shared".into(),
                    payload: "{}".into(),
                })),
                Mutation::Put(Record::Status(status(account, "s"))),
            ]);
        }
        writes.extend([
            Mutation::Put(Record::Room(Room {
                room_key: "r".into(),
                platform_id: "r".into(),
                last_message_key: None,
            })),
            Mutation::Put(Record::Message(message("remote", false))),
            Mutation::Put(Record::ListMember(ListMember {
                list_key: "shared".into(),
                member_key: "u".into(),
            })),
            Mutation::Put(Record::User(User {
                user_key: "u".into(),
                payload: "{}".into(),
                ..Default::default()
            })),
        ]);
        db.write(&cache_epoch, writes).await.unwrap();
        let p = reset(&db, &cache_epoch, "mixed").await;
        let mut mixed = page(&p, vec![], vec![row("mixed", "s", 1)]);
        mixed.account_keys = vec!["a".into(), "b".into()];
        db.write(&cache_epoch, vec![Mutation::CommitPage(mixed)])
            .await
            .unwrap();
        db.write(&cache_epoch, vec![Mutation::ClearAccount("a".into())])
            .await
            .unwrap();
        assert!(read_page(&db, "mixed").await.rows.is_empty());
        let s: std::collections::BTreeMap<_, _> = stats(&db).await.into_iter().collect();
        assert_eq!(s["cache_statuses"], 1);
        assert_eq!(s["cache_messages"], 1);
        assert_eq!(s["cache_list_members"], 1);
        assert_eq!(s["cache_users"], 1);
        db.write(&cache_epoch, vec![Mutation::ClearAccount("b".into())])
            .await
            .unwrap();
        let s: std::collections::BTreeMap<_, _> = stats(&db).await.into_iter().collect();
        assert_eq!(s["cache_messages"], 0);
        assert_eq!(s["cache_list_members"], 0);
        assert_eq!(s["cache_users"], 1);
    });
}

#[test]
fn hydration_chunks_and_identity_reads_keep_large_values() {
    block_on(async {
        let directory = tempfile::tempdir().unwrap();
        let db = CacheDatabase::open(directory.path().join("cache.db"))
            .await
            .unwrap();
        let cache_epoch = epoch(&db).await;
        let p = reset(&db, &cache_epoch, "home").await;
        let statuses: Vec<_> = (0..850).map(|n| status("a", &n.to_string())).collect();
        let rows: Vec<_> = (0..850).map(|n| row("home", &n.to_string(), n)).collect();
        db.write(
            &cache_epoch,
            vec![Mutation::CommitPage(page(&p, statuses, rows))],
        )
        .await
        .unwrap();
        let Snapshot::Page(p) = db
            .query(Query::TimelinePage {
                paging_key: "home".into(),
                range: Range {
                    offset: 0,
                    limit: 1000,
                    descending: true,
                },
            })
            .await
            .unwrap()
        else {
            panic!()
        };
        assert_eq!(p.statuses.len(), 850);
        assert_eq!(p.rows[0].sort_id, 849);
        assert!(p.statuses[0].payload.contains("9007199254740993"));
        let Snapshot::Identities(ids) = db
            .query(Query::TimelineIdentities {
                paging_key: "home".into(),
                range: range(),
            })
            .await
            .unwrap()
        else {
            panic!()
        };
        assert_eq!(ids.rows.len(), 100);
        assert!(ids.has_more);
        assert_eq!(key(&["😀", "a:b"]), "4:😀3:a:b");
        assert_ne!(key(&["a", "bc"]), key(&["ab", "c"]));
        let before = stats(&db).await;
        let mut bad = status("a", "invalid");
        bad.payload = "{".into();
        assert!(
            db.write(&cache_epoch, vec![Mutation::Put(Record::Status(bad))])
                .await
                .is_err()
        );
        assert_eq!(stats(&db).await, before);
        assert!(!format!("{:?}", p.statuses[0]).contains("payload"));
    });
}
