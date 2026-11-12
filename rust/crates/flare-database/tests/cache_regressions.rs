#![cfg(not(target_arch = "wasm32"))]
use flare_database::{
    CacheDatabase, Error,
    cache::{
        self, Mutation, PageGuard, PageWrite, Query, Range, Record, Snapshot, Status, TimelineRow,
        Translation, key,
    },
};
use futures::{FutureExt, StreamExt, executor::block_on};

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
fn range(offset: i64, limit: i64) -> Range {
    Range {
        offset,
        limit,
        descending: false,
    }
}
fn status() -> Status {
    Status {
        account: "a".into(),
        status_key: "s".into(),
        payload: "{}".into(),
        ..Default::default()
    }
}
async fn make_page(db: &CacheDatabase, epoch: &str) {
    let metadata = db
        .write(
            epoch,
            vec![Mutation::ResetTimeline {
                paging_key: "home".into(),
                account_keys: vec!["a".into()],
            }],
        )
        .await
        .unwrap()
        .pages
        .remove(0);
    db.write(
        epoch,
        vec![Mutation::CommitPage(PageWrite {
            paging_key: "home".into(),
            expected: PageGuard {
                generation: metadata.generation,
                revision: metadata.revision,
            },
            statuses: vec![status()],
            references: vec![],
            items: vec![TimelineRow {
                paging_key: "home".into(),
                timeline_id: "t".into(),
                status_id: key(&["a", "s"]),
                ..Default::default()
            }],
            presentations: vec![],
            remove_ids: vec![],
            next_key: None,
            keep_next_key: false,
            prev_key: None,
            source_heads: "{}".into(),
            account_keys: vec!["a".into()],
        })],
    )
    .await
    .unwrap();
}

#[test]
fn dropping_a_subscription_releases_its_snapshot_without_another_write() {
    block_on(async {
        let (_directory, db, epoch) = fixture().await;
        make_page(&db, &epoch).await;
        let mut subscription = db
            .observe(Query::TimelinePage {
                paging_key: "home".into(),
                range: range(0, 10),
            })
            .await
            .unwrap();
        let snapshot = subscription.next().await.unwrap().unwrap();
        let weak = std::sync::Arc::downgrade(&snapshot);
        drop(snapshot);
        drop(subscription);
        db.query(Query::Info).await.unwrap();
        assert!(
            weak.upgrade().is_none(),
            "unsubscribe must release the observer without waiting for a write"
        );
    });
}

#[test]
fn full_pages_publish_data_changes_while_render_identities_stay_asleep() {
    block_on(async {
        let (_directory, db, epoch) = fixture().await;
        make_page(&db, &epoch).await;
        let translation = Translation {
            id: key(&["Status", &key(&["a", "s"]), "zh"]),
            entity_type: "Status".into(),
            entity_key: key(&["a", "s"]),
            target_language: "zh".into(),
            source_hash: "hash".into(),
            status: "Pending".into(),
            display_mode: "Auto".into(),
            updated_at: 1,
            ..Default::default()
        };
        db.write(
            &epoch,
            vec![Mutation::Put(Record::Translation(translation.clone()))],
        )
        .await
        .unwrap();
        let query = Query::TimelinePage {
            paging_key: "home".into(),
            range: range(0, 10),
        };
        let mut pages = db.observe(query.clone()).await.unwrap();
        pages.next().await.unwrap().unwrap();
        let mut identities = db
            .observe(Query::TimelineIdentities {
                paging_key: "home".into(),
                range: range(0, 10),
            })
            .await
            .unwrap();
        identities.next().await.unwrap().unwrap();
        db.write(
            &epoch,
            vec![Mutation::TranslationDisplay {
                id: translation.id,
                display_mode: "Auto".into(),
                updated_at: 2,
            }],
        )
        .await
        .unwrap();
        let event = pages
            .next()
            .now_or_never()
            .expect("timestamp updates must refresh full snapshots")
            .unwrap()
            .unwrap();
        assert_eq!(event.as_ref(), &db.query(query.clone()).await.unwrap());
        assert!(identities.next().now_or_never().is_none());
        let Snapshot::Status(Some(mut row)) =
            db.query(Query::Status(key(&["a", "s"]))).await.unwrap()
        else {
            panic!()
        };
        let expected_version = row.version.clone();
        row.text = Some("new search text".into());
        db.write(
            &epoch,
            vec![Mutation::UpdateStatus {
                expected_version,
                status: row,
            }],
        )
        .await
        .unwrap();
        let event = pages
            .next()
            .now_or_never()
            .expect("new status versions must refresh full snapshots")
            .unwrap()
            .unwrap();
        assert_eq!(event.as_ref(), &db.query(query).await.unwrap());
        assert!(identities.next().now_or_never().is_none());
    });
}

fn message(id: &str, timestamp: i64, local: bool) -> cache::Message {
    cache::Message {
        message_key: id.into(),
        room_key: "r".into(),
        user_key: "u".into(),
        timestamp,
        payload: "{}".into(),
        is_local: local,
        ..Default::default()
    }
}
async fn room(db: &CacheDatabase) -> (i64, Option<String>) {
    let Snapshot::Joined(joined) = db
        .query(Query::Room {
            account: "a".into(),
            room_key: "r".into(),
        })
        .await
        .unwrap()
    else {
        panic!()
    };
    let mut sort = 0;
    let mut last = None;
    for record in joined.records {
        match record {
            Record::RoomTimeline(r) => sort = r.sort_id,
            Record::Room(r) => last = r.last_message_key,
            _ => {}
        }
    }
    (sort, last)
}
async fn make_room(db: &CacheDatabase, epoch: &str) {
    db.write(
        epoch,
        vec![
            Mutation::Put(Record::RoomTimeline(cache::RoomTimeline {
                account: "a".into(),
                room_key: "r".into(),
                payload: "{}".into(),
                ..Default::default()
            })),
            Mutation::Put(Record::Room(cache::Room {
                room_key: "r".into(),
                platform_id: "test".into(),
                last_message_key: None,
            })),
        ],
    )
    .await
    .unwrap();
}

#[test]
fn correcting_remote_message_times_reorders_rooms_without_treating_backfill_as_a_refresh() {
    block_on(async {
        let (_directory, db, epoch) = fixture().await;
        make_room(&db, &epoch).await;
        db.write(
            &epoch,
            vec![
                Mutation::Put(Record::RoomTimeline(cache::RoomTimeline {
                    account: "a".into(),
                    room_key: "r".into(),
                    sort_id: 500,
                    payload: "{}".into(),
                    ..Default::default()
                })),
                Mutation::Put(Record::Message(message("old", 100, false))),
                Mutation::Put(Record::Message(message("latest", 200, false))),
            ],
        )
        .await
        .unwrap();
        assert_eq!(room(&db).await, (500, Some("latest".into())));
        db.write(
            &epoch,
            vec![Mutation::Put(Record::Message(message("latest", 80, false)))],
        )
        .await
        .unwrap();
        assert_eq!(room(&db).await, (100, Some("old".into())));
        db.write(
            &epoch,
            vec![Mutation::Put(Record::Message(message("old", 300, false)))],
        )
        .await
        .unwrap();
        assert_eq!(room(&db).await, (300, Some("old".into())));
        db.write(
            &epoch,
            vec![Mutation::Put(Record::Message(message(
                "older-backfill",
                10,
                false,
            )))],
        )
        .await
        .unwrap();
        assert_eq!(room(&db).await, (300, Some("old".into())));
    });
}

#[test]
fn room_previews_ignore_local_sends_and_order_follows_deletion_and_ack() {
    block_on(async {
        let (_directory, db, epoch) = fixture().await;
        make_room(&db, &epoch).await;
        db.write(
            &epoch,
            vec![
                Mutation::Put(Record::Message(message("old", 100, false))),
                Mutation::Put(Record::Message(message("new", 200, false))),
            ],
        )
        .await
        .unwrap();
        assert_eq!(room(&db).await, (200, Some("new".into())));
        db.write(
            &epoch,
            vec![Mutation::Put(Record::Message(message("local", 300, true)))],
        )
        .await
        .unwrap();
        assert_eq!(room(&db).await, (200, Some("new".into())));
        db.write(
            &epoch,
            vec![Mutation::Delete(cache::Delete::Message("new".into()))],
        )
        .await
        .unwrap();
        assert_eq!(room(&db).await, (100, Some("old".into())));
        db.write(
            &epoch,
            vec![Mutation::AcknowledgeMessage {
                local_key: "local".into(),
                expected_revision: 0,
                remote: message("ack", 150, false),
            }],
        )
        .await
        .unwrap();
        assert_eq!(room(&db).await, (150, Some("ack".into())));
        db.write(
            &epoch,
            vec![
                Mutation::Delete(cache::Delete::Message("ack".into())),
                Mutation::Delete(cache::Delete::Message("old".into())),
            ],
        )
        .await
        .unwrap();
        assert_eq!(room(&db).await, (0, None));
    });
}

#[test]
#[allow(
    clippy::too_many_lines,
    reason = "One scenario verifies replacement rollback, observation and shared-room isolation"
)]
fn room_message_replacement_is_atomic_scoped_and_observable() {
    block_on(async {
        let (_directory, db, epoch) = fixture().await;
        make_room(&db, &epoch).await;
        db.write(
            &epoch,
            vec![
                Mutation::Put(Record::RoomTimeline(cache::RoomTimeline {
                    account: "b".into(),
                    room_key: "r".into(),
                    unread_count: 5,
                    payload: "{}".into(),
                    ..Default::default()
                })),
                Mutation::Put(Record::RoomMember(cache::RoomMember {
                    room_key: "r".into(),
                    user_key: "u".into(),
                })),
                Mutation::Put(Record::Message(message("old", 10, false))),
                Mutation::Put(Record::Message(message("local", 20, true))),
            ],
        )
        .await
        .unwrap();
        let Snapshot::Message(Some(old)) = db.query(Query::Message("old".into())).await.unwrap()
        else {
            panic!()
        };
        assert_eq!(old.room_key, "r");
        assert_eq!(
            db.query(Query::Message("absent".into())).await.unwrap(),
            Snapshot::Message(None)
        );
        let query = Query::RoomMessages {
            room_key: "r".into(),
            range: range(0, 10),
        };
        let before = db.query(query.clone()).await.unwrap();
        let mut stream = db.observe(query.clone()).await.unwrap();
        stream.next().await.unwrap().unwrap();
        let mut invalid = message("invalid", 3, false);
        invalid.payload = "{".into();
        assert!(
            db.write(
                &epoch,
                vec![Mutation::ReplaceRoomMessages {
                    room_key: "r".into(),
                    messages: vec![message("first", 2, false), invalid]
                }]
            )
            .await
            .is_err()
        );
        assert_eq!(before, db.query(query.clone()).await.unwrap());
        assert!(stream.next().now_or_never().is_none());
        let mut foreign = message("foreign", 4, false);
        foreign.room_key = "other".into();
        db.write(
            &epoch,
            vec![Mutation::Put(Record::Message(foreign.clone()))],
        )
        .await
        .unwrap();
        assert!(
            db.write(
                &epoch,
                vec![Mutation::ReplaceRoomMessages {
                    room_key: "r".into(),
                    messages: vec![foreign]
                }]
            )
            .await
            .is_err()
        );
        assert_eq!(before, db.query(query.clone()).await.unwrap());
        assert_eq!(
            db.write(
                "old-epoch",
                vec![Mutation::ReplaceRoomMessages {
                    room_key: "r".into(),
                    messages: vec![]
                }]
            )
            .await
            .unwrap_err(),
            Error::Conflict
        );
        db.write(
            &epoch,
            vec![
                Mutation::ReplaceRoomMessages {
                    room_key: "r".into(),
                    messages: vec![message("fresh", 3, false)],
                },
                Mutation::ClearUnread {
                    account: "a".into(),
                    room_key: "r".into(),
                },
            ],
        )
        .await
        .unwrap();
        assert_eq!(
            stream
                .next()
                .now_or_never()
                .expect("replacement must publish")
                .unwrap()
                .unwrap()
                .as_ref(),
            &db.query(query).await.unwrap()
        );
        assert_eq!(
            db.query(Query::Message("old".into())).await.unwrap(),
            Snapshot::Message(None)
        );
        assert_eq!(
            db.query(Query::Message("local".into())).await.unwrap(),
            Snapshot::Message(None)
        );
        assert!(matches!(
            db.query(Query::Message("foreign".into())).await.unwrap(),
            Snapshot::Message(Some(_))
        ));
        assert_eq!(room(&db).await, (3, Some("fresh".into())));
        let Snapshot::Joined(shared) = db
            .query(Query::Room {
                account: "b".into(),
                room_key: "r".into(),
            })
            .await
            .unwrap()
        else {
            panic!()
        };
        assert!(
            shared
                .records
                .iter()
                .any(|r| matches!(r,Record::RoomTimeline(t) if t.unread_count==5))
        );
        assert!(
            shared
                .records
                .iter()
                .any(|r| matches!(r, Record::RoomMember(_)))
        );
        db.write(
            &epoch,
            vec![Mutation::ReplaceRoomMessages {
                room_key: "r".into(),
                messages: vec![],
            }],
        )
        .await
        .unwrap();
        assert_eq!(room(&db).await, (0, None));
    });
}

#[test]
fn member_pages_join_profiles_before_applying_the_page_boundary() {
    block_on(async {
        let (_directory, db, epoch) = fixture().await;
        let mut writes = Vec::new();
        for member in ["a", "b", "c", "d"] {
            writes.push(Mutation::Put(Record::ListMember(cache::ListMember {
                list_key: "list".into(),
                member_key: member.into(),
            })));
            writes.push(Mutation::Put(Record::User(cache::User {
                user_key: member.into(),
                payload: "{}".into(),
                ..Default::default()
            })));
        }
        writes.extend([
            Mutation::Delete(cache::Delete::User("a".into())),
            Mutation::Delete(cache::Delete::User("c".into())),
        ]);
        db.write(&epoch, writes).await.unwrap();
        let Snapshot::RecordsPage(keys) = db
            .query(Query::ListMemberKeys {
                list_key: "list".into(),
                range: range(0, 10),
            })
            .await
            .unwrap()
        else {
            panic!()
        };
        assert_eq!(keys.records.len(), 4);
        assert!(keys.users.is_empty());
        for (offset, expected, more) in [
            (0, Some("b"), true),
            (1, Some("d"), false),
            (2, None, false),
        ] {
            let Snapshot::RecordsPage(page) = db
                .query(Query::ListMembers {
                    list_key: "list".into(),
                    range: range(offset, 1),
                })
                .await
                .unwrap()
            else {
                panic!("member pages need explicit continuation metadata")
            };
            assert_eq!(page.users.first().map(|u| u.user_key.as_str()), expected);
            assert_eq!(page.has_more, more);
            assert_eq!(page.records.len(), page.users.len());
        }
        let query = Query::ListMembers {
            list_key: "list".into(),
            range: range(0, 1),
        };
        let mut stream = db.observe(query.clone()).await.unwrap();
        stream.next().await.unwrap().unwrap();
        db.write(
            &epoch,
            vec![Mutation::Put(Record::User(cache::User {
                user_key: "a".into(),
                payload: "{}".into(),
                ..Default::default()
            }))],
        )
        .await
        .unwrap();
        assert_eq!(
            stream
                .next()
                .now_or_never()
                .expect("new profile changes the visible page")
                .unwrap()
                .unwrap()
                .as_ref(),
            &db.query(query).await.unwrap()
        );
    });
}

#[test]
fn cached_search_hydrates_quotes_and_translations_in_the_search_snapshot() {
    block_on(async {
        let (_directory, db, epoch) = fixture().await;
        let mut root = status();
        root.text = Some("literal %_\\".into());
        let quoted = Status {
            account: "a".into(),
            status_key: "quoted".into(),
            payload: "{}".into(),
            ..Default::default()
        };
        let quoted_id = key(&["a", "quoted"]);
        let translation = Translation {
            id: key(&["Status", &quoted_id, "zh"]),
            entity_type: "Status".into(),
            entity_key: quoted_id.clone(),
            target_language: "zh".into(),
            source_hash: "hash".into(),
            status: "Completed".into(),
            display_mode: "Translated".into(),
            payload: Some("{\"future\":9007199254740993}".into()),
            ..Default::default()
        };
        db.write(
            &epoch,
            vec![
                Mutation::Put(Record::Status(root)),
                Mutation::Put(Record::Status(quoted)),
                Mutation::Put(Record::Translation(translation)),
                Mutation::ReplaceReferences(cache::ReferenceSet {
                    status_id: key(&["a", "s"]),
                    references: vec![cache::Reference {
                        status_id: key(&["a", "s"]),
                        reference_type: "Quote".into(),
                        reference_status_id: quoted_id.clone(),
                        reference_order: 0,
                    }],
                }),
            ],
        )
        .await
        .unwrap();
        let query = Query::SearchStatuses {
            text: "%_\\".into(),
            range: range(0, 1),
        };
        let Snapshot::StatusPage(page) = db.query(query.clone()).await.unwrap() else {
            panic!("search must return a hydrated status page")
        };
        assert_eq!(page.root_ids, vec![key(&["a", "s"])]);
        assert_eq!(page.bundle.statuses.len(), 2);
        assert_eq!(page.bundle.references.len(), 1);
        assert_eq!(
            page.bundle.translations[0].payload.as_deref(),
            Some("{\"future\":9007199254740993}")
        );
        assert!(!page.has_more);
        let mut stream = db.observe(query.clone()).await.unwrap();
        stream.next().await.unwrap().unwrap();
        db.write(
            &epoch,
            vec![Mutation::Delete(cache::Delete::Status(quoted_id))],
        )
        .await
        .unwrap();
        let event = stream
            .next()
            .now_or_never()
            .expect("referenced content changes the search page")
            .unwrap()
            .unwrap();
        assert_eq!(event.as_ref(), &db.query(query).await.unwrap());
        let Snapshot::StatusPage(page) = event.as_ref() else {
            panic!()
        };
        assert_eq!(page.bundle.statuses.len(), 1);
        assert_eq!(page.bundle.references.len(), 1);
        assert!(page.bundle.translations.is_empty());
    });
}

#[test]
fn emoji_history_can_read_supported_codes_older_than_the_first_hundred() {
    block_on(async {
        let (_directory, db, epoch) = fixture().await;
        db.write(
            &epoch,
            (0..150)
                .map(|i| {
                    Mutation::Put(Record::EmojiHistory(cache::EmojiHistory {
                        account: "a".into(),
                        short_code: format!("{}{i}", if i < 50 { "keep" } else { "removed" }),
                        last_use: i,
                    }))
                })
                .collect(),
        )
        .await
        .unwrap();
        db.write(
            &epoch,
            vec![Mutation::Put(Record::EmojiHistory(cache::EmojiHistory {
                account: "b".into(),
                short_code: "foreign".into(),
                last_use: 999,
            }))],
        )
        .await
        .unwrap();
        for (offset, count, more, prefix) in [
            (0, 100, true, "removed"),
            (100, 50, false, "keep"),
            (150, 0, false, ""),
        ] {
            let Snapshot::RecordsPage(page) = db
                .query(Query::EmojiHistory {
                    account: "a".into(),
                    range: Range {
                        descending: true,
                        ..range(offset, 100)
                    },
                })
                .await
                .unwrap()
            else {
                panic!()
            };
            assert_eq!(page.records.len(), count);
            assert_eq!(page.has_more, more);
            assert!(page.records.iter().all(|r|matches!(r,Record::EmojiHistory(h) if h.account=="a" && h.short_code.starts_with(prefix))));
        }
        db.write(
            &epoch,
            vec![Mutation::Delete(cache::Delete::EmojiHistory("a".into()))],
        )
        .await
        .unwrap();
        let Snapshot::RecordsPage(foreign) = db
            .query(Query::EmojiHistory {
                account: "b".into(),
                range: range(0, 10),
            })
            .await
            .unwrap()
        else {
            panic!()
        };
        assert_eq!(foreign.records.len(), 1);
    });
}

#[derive(diesel::QueryableByName)]
struct Plan {
    #[diesel(sql_type=diesel::sql_types::Text)]
    detail: String,
}
#[test]
fn status_reference_and_translation_propagation_use_reverse_indexes() {
    block_on(async {
        use diesel::{Connection, RunQueryDsl};
        let (directory, _db, _epoch) = fixture().await;
        let mut connection = diesel::SqliteConnection::establish(
            directory.path().join("cache.db").to_str().unwrap(),
        )
        .unwrap();
        for trigger in [
            "cache_statuses_INSERT",
            "cache_references_INSERT",
            "cache_translations_INSERT",
        ] {
            let sql = diesel::sql_query("SELECT sql AS detail FROM sqlite_master WHERE name=?")
                .bind::<diesel::sql_types::Text, _>(trigger)
                .get_result::<Plan>(&mut connection)
                .unwrap()
                .detail;
            let body = &sql[sql.find(" BEGIN ").unwrap() + 7..];
            for statement in body.split(';').filter(|s| s.contains("cache_timelines")) {
                let statement = statement
                    .replace("NEW.entity_key", "'absent'")
                    .replace("NEW.entity_type", "'Status'")
                    .replace("NEW.status_id", "'absent'")
                    .replace("NEW.id", "'absent'");
                let plan = diesel::sql_query(format!("EXPLAIN QUERY PLAN {statement}"))
                    .load::<Plan>(&mut connection)
                    .unwrap();
                for step in &plan {
                    assert!(
                        !["SCAN cache_timelines", "SCAN t", "SCAN r", "SCAN p"]
                            .iter()
                            .any(|table| step.detail == *table
                                || step.detail.starts_with(&format!("{table} "))),
                        "{trigger}: {}",
                        step.detail
                    );
                }
                assert!(
                    plan.iter()
                        .any(|s| s.detail.contains("cache_presentation_target")),
                    "{trigger} must walk the reverse presentation index"
                );
            }
        }
    });
}

#[test]
#[ignore = "Manual timing; the deterministic query-plan regression runs in the normal suite"]
fn unrelated_status_writes_at_different_cache_sizes() {
    block_on(async {
        for count in [500, 10_000] {
            let (_directory, db, epoch) = fixture().await;
            let metadata = db
                .write(
                    &epoch,
                    vec![Mutation::ResetTimeline {
                        paging_key: "home".into(),
                        account_keys: vec!["a".into()],
                    }],
                )
                .await
                .unwrap()
                .pages
                .remove(0);
            let rows = (0..count)
                .map(|n| TimelineRow {
                    paging_key: "home".into(),
                    timeline_id: format!("row{n}"),
                    status_id: key(&["a", "s"]),
                    sort_id: n,
                    ..Default::default()
                })
                .collect();
            db.write(
                &epoch,
                vec![Mutation::CommitPage(PageWrite {
                    paging_key: "home".into(),
                    expected: PageGuard {
                        generation: metadata.generation,
                        revision: metadata.revision,
                    },
                    statuses: vec![status()],
                    references: vec![],
                    items: rows,
                    presentations: vec![],
                    remove_ids: vec![],
                    next_key: None,
                    keep_next_key: false,
                    prev_key: None,
                    source_heads: "{}".into(),
                    account_keys: vec!["a".into()],
                })],
            )
            .await
            .unwrap();
            let started = std::time::Instant::now();
            for revision in 0..20 {
                db.write(
                    &epoch,
                    vec![Mutation::Put(Record::Status(Status {
                        account: "a".into(),
                        status_key: "not-referenced-by-any-page".into(),
                        payload: format!("{{\"revision\":{revision}}}"),
                        ..Default::default()
                    }))],
                )
                .await
                .unwrap();
            }
            println!(
                "{count} timeline rows: 20 unrelated status writes in {:.2} ms",
                started.elapsed().as_secs_f64() * 1000.0
            );
        }
    });
}
