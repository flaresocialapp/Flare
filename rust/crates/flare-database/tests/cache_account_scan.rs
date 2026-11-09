#![cfg(not(target_arch = "wasm32"))]
use flare_database::{CacheDatabase, cache::*};
use futures::{FutureExt, StreamExt, executor::block_on};

fn status(account: &str, id: &str) -> Status {
    Status {
        account: account.into(),
        status_key: id.into(),
        payload: "{}".into(),
        ..Default::default()
    }
}
async fn page(db: &CacheDatabase, epoch: &str, paging_key: &str, rows: Vec<(&str, Status)>) {
    let version = db
        .write(
            epoch,
            vec![Mutation::ResetTimeline {
                paging_key: paging_key.into(),
                account_keys: vec!["a".into(), "b".into()],
            }],
        )
        .await
        .unwrap()
        .pages
        .remove(0);
    let items = rows
        .iter()
        .map(|(id, status)| TimelineRow {
            paging_key: paging_key.into(),
            timeline_id: (*id).into(),
            status_id: key(&[&status.account, &status.status_key]),
            ..Default::default()
        })
        .collect();
    db.write(
        epoch,
        vec![Mutation::CommitPage(PageWrite {
            paging_key: paging_key.into(),
            expected: PageGuard {
                generation: version.generation,
                revision: version.revision,
            },
            statuses: rows.into_iter().map(|(_, s)| s).collect(),
            references: vec![],
            items,
            presentations: vec![],
            remove_ids: vec![],
            next_key: Some("keep-me".into()),
            keep_next_key: false,
            prev_key: None,
            source_heads: "{}".into(),
            account_keys: vec!["a".into(), "b".into()],
        })],
    )
    .await
    .unwrap();
}

#[test]
#[allow(
    clippy::too_many_lines,
    reason = "A hydrated scan scenario covers inline parents, nested translations and subscription filtering"
)]
fn account_scan_observes_inline_parent_references_and_metadata_without_cross_account_roots() {
    block_on(async {
        let directory = tempfile::tempdir().unwrap();
        let db = CacheDatabase::open(directory.path().join("cache.db"))
            .await
            .unwrap();
        let Snapshot::Info(info) = db.query(Query::Info).await.unwrap() else {
            panic!()
        };
        page(
            &db,
            &info.epoch,
            "mixed",
            vec![("own", status("a", "s")), ("foreign", status("b", "s"))],
        )
        .await;
        let Snapshot::Records(rows) = db.query(Query::PagingKey("mixed".into())).await.unwrap()
        else {
            panic!()
        };
        let Record::PagingKey(metadata) = &rows[0] else {
            panic!()
        };
        let parent = key(&["a", "parent"]);
        let nested = key(&["b", "nested"]);
        let translation = Translation {
            entity_type: "Status".into(),
            entity_key: nested.clone(),
            target_language: "zh".into(),
            source_hash: "h".into(),
            status: "Completed".into(),
            display_mode: "Auto".into(),
            payload: Some("{}".into()),
            updated_at: 1,
            ..Default::default()
        };
        db.write(
            &info.epoch,
            vec![
                Mutation::CommitPage(PageWrite {
                    paging_key: "mixed".into(),
                    expected: PageGuard {
                        generation: metadata.generation.clone(),
                        revision: metadata.revision,
                    },
                    statuses: vec![status("a", "parent"), status("b", "nested")],
                    references: vec![ReferenceSet {
                        status_id: parent.clone(),
                        references: vec![Reference {
                            status_id: parent.clone(),
                            reference_type: "Quote".into(),
                            reference_status_id: nested.clone(),
                            reference_order: 0,
                        }],
                    }],
                    items: vec![],
                    presentations: vec![PresentationSet {
                        timeline_id: "own".into(),
                        references: vec![PresentationReference {
                            paging_key: "mixed".into(),
                            timeline_id: "own".into(),
                            presentation_type: "InlineParent".into(),
                            reference_status_id: parent.clone(),
                            reference_order: 0,
                        }],
                    }],
                    remove_ids: vec![],
                    next_key: None,
                    keep_next_key: true,
                    prev_key: None,
                    source_heads: "{}".into(),
                    account_keys: vec!["a".into(), "b".into()],
                }),
                Mutation::Put(Record::Translation(translation)),
            ],
        )
        .await
        .unwrap();
        let query = Query::AccountTimelines {
            account: "a".into(),
            after: None,
            limit: 100,
        };
        let mut stream = db.observe(query.clone()).await.unwrap();
        let initial = stream.next().await.unwrap().unwrap();
        let Snapshot::TimelineScan(scan) = initial.as_ref() else {
            panic!()
        };
        assert!(scan.next.is_none());
        assert_eq!(scan.pages.len(), 1);
        let page = &scan.pages[0];
        assert_eq!(page.rows.len(), 1);
        assert_eq!(page.rows[0].timeline_id, "own");
        assert_eq!(page.statuses.len(), 3);
        assert_eq!(page.presentations[0].reference_status_id, parent);
        assert_eq!(page.references[0].reference_status_id, nested);
        assert_eq!(page.translations[0].updated_at, 1);
        db.write(
            &info.epoch,
            vec![Mutation::TranslationDisplay {
                id: key(&["Status", &nested, "zh"]),
                display_mode: "Auto".into(),
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
        db.write(
            &info.epoch,
            vec![Mutation::Put(Record::Emoji(Emoji {
                host: "unrelated".into(),
                payload: "[]".into(),
            }))],
        )
        .await
        .unwrap();
        assert!(stream.next().now_or_never().is_none());
        assert!(
            db.query(Query::AccountTimelines {
                account: String::new(),
                after: None,
                limit: 1
            })
            .await
            .is_err()
        );
    });
}

#[test]
#[allow(
    clippy::too_many_lines,
    reason = "One end-to-end scan removes cursor rows while retaining mixed-account content"
)]
fn account_scan_hydrates_every_timeline_and_can_resume_after_removing_its_cursor_row() {
    block_on(async {
        let directory = tempfile::tempdir().unwrap();
        let db = CacheDatabase::open(directory.path().join("cache.db"))
            .await
            .unwrap();
        let Snapshot::Info(info) = db.query(Query::Info).await.unwrap() else {
            panic!()
        };
        page(
            &db,
            &info.epoch,
            "home",
            vec![
                ("shared", status("a", "s")),
                ("foreign", status("b", "s")),
                ("tail", status("a", "z")),
            ],
        )
        .await;
        page(
            &db,
            &info.epoch,
            "details",
            vec![("shared", status("a", "s"))],
        )
        .await;
        let quoted = key(&["a", "quoted"]);
        db.write(
            &info.epoch,
            vec![
                Mutation::Put(Record::Status(status("a", "quoted"))),
                Mutation::ReplaceReferences(ReferenceSet {
                    status_id: key(&["a", "s"]),
                    references: vec![Reference {
                        status_id: key(&["a", "s"]),
                        reference_type: "Quote".into(),
                        reference_status_id: quoted.clone(),
                        reference_order: 0,
                    }],
                }),
                Mutation::Put(Record::Translation(Translation {
                    entity_type: "Status".into(),
                    entity_key: quoted,
                    target_language: "zh".into(),
                    source_hash: "h".into(),
                    status: "Completed".into(),
                    display_mode: "Translated".into(),
                    payload: Some("{}".into()),
                    ..Default::default()
                })),
            ],
        )
        .await
        .unwrap();
        let mut after = None;
        let mut visited = Vec::new();
        loop {
            let Snapshot::TimelineScan(scan) = db
                .query(Query::AccountTimelines {
                    account: "a".into(),
                    after: after.clone(),
                    limit: 1,
                })
                .await
                .unwrap()
            else {
                panic!()
            };
            assert_eq!(scan.info.epoch, info.epoch);
            assert_eq!(scan.pages.len(), 1);
            let fragment = scan.pages.into_iter().next().unwrap();
            assert_eq!(fragment.rows.len(), 1);
            let row = &fragment.rows[0];
            visited.push((row.paging_key.clone(), row.timeline_id.clone()));
            if row.status_id == key(&["a", "s"]) {
                assert_eq!(fragment.references.len(), 1);
                assert_eq!(fragment.translations.len(), 1);
            }
            let metadata = fragment.metadata.unwrap();
            db.write(
                &info.epoch,
                vec![Mutation::CommitPage(PageWrite {
                    paging_key: row.paging_key.clone(),
                    expected: PageGuard {
                        generation: metadata.generation,
                        revision: metadata.revision,
                    },
                    statuses: vec![],
                    references: vec![],
                    items: vec![],
                    presentations: vec![],
                    remove_ids: vec![row.timeline_id.clone()],
                    next_key: None,
                    keep_next_key: true,
                    prev_key: metadata.prev_key,
                    source_heads: metadata.source_heads,
                    account_keys: vec!["a".into(), "b".into()],
                })],
            )
            .await
            .unwrap();
            after = scan.next;
            if after.is_none() {
                break;
            }
        }
        assert_eq!(
            visited,
            vec![
                ("details".into(), "shared".into()),
                ("home".into(), "shared".into()),
                ("home".into(), "tail".into())
            ]
        );
        let Snapshot::Page(home) = db
            .query(Query::TimelinePage {
                paging_key: "home".into(),
                range: Range {
                    offset: 0,
                    limit: 10,
                    descending: false,
                },
            })
            .await
            .unwrap()
        else {
            panic!()
        };
        assert_eq!(home.rows.len(), 1);
        assert_eq!(home.rows[0].timeline_id, "foreign");
        assert_eq!(home.metadata.unwrap().next_key.as_deref(), Some("keep-me"));
        assert!(matches!(
            db.query(Query::Status(key(&["a", "s"]))).await.unwrap(),
            Snapshot::Status(Some(_))
        ));
        let Snapshot::TimelineScan(empty) = db
            .query(Query::AccountTimelines {
                account: "a".into(),
                after: None,
                limit: 100,
            })
            .await
            .unwrap()
        else {
            panic!()
        };
        assert!(empty.pages.is_empty());
        assert!(empty.next.is_none());
        for limit in [0, 1001] {
            assert!(
                db.query(Query::AccountTimelines {
                    account: "a".into(),
                    after: None,
                    limit
                })
                .await
                .is_err()
            );
        }
    });
}
