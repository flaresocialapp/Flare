#![cfg(not(target_arch = "wasm32"))]

use diesel::{Connection, connection::SimpleConnection};
use flare_database::{
    AgentDatabase, AppDatabase, AppQuery, Error,
    agent::{self, Mutation as M, Query as Q, Snapshot as S},
};
use futures::{FutureExt, StreamExt, executor::block_on, future::join_all};

fn new_message(id: &str, role: &str, at: i64) -> agent::NewMessage {
    agent::NewMessage {
        message_id: id.into(),
        role: role.into(),
        text: format!("text {id}"),
        content_json: "[ {\"future\":9007199254740993} ]".into(),
        message_json: "{\"fake\":true,\"unknown\":null}".into(),
        created_at: at,
    }
}
async fn claim(db: &AgentDatabase, id: &str) -> String {
    db.write(vec![M::ClaimRun {
        conversation_id: id.into(),
        now: 1,
    }])
    .await
    .unwrap()
    .runs
    .remove(0)
    .run_id
}
fn insert(id: &str, run: &str, rows: Vec<agent::NewMessage>) -> M {
    M::InsertMessages {
        conversation_id: id.into(),
        run_id: run.into(),
        placement: agent::Placement::Append,
        messages: rows,
    }
}
async fn history(db: &AgentDatabase, id: &str) -> Option<agent::History> {
    let S::History(value) = db.query(Q::History(id.into())).await.unwrap() else {
        panic!()
    };
    value
}
fn old_database(path: &std::path::Path, version: i32) {
    let mut connection = diesel::SqliteConnection::establish(path.to_str().unwrap()).unwrap();
    connection
        .batch_execute(
            "CREATE TABLE agent_conversations(conversationId TEXT PRIMARY KEY,title TEXT);
        CREATE TABLE agent_messages(conversationId TEXT,position INTEGER, text TEXT);
        CREATE TABLE room_master_table(id INTEGER PRIMARY KEY,identity_hash TEXT);
        INSERT INTO room_master_table VALUES(42,'fake-agent');
        INSERT INTO agent_conversations VALUES('old','old history');
        INSERT INTO agent_messages VALUES('old',0,'old payload');",
        )
        .unwrap();
    if version >= 10 {
        connection.batch_execute("CREATE TABLE agent_contexts(conversationId TEXT PRIMARY KEY,summary TEXT,throughPosition INTEGER)").unwrap();
    }
    connection
        .batch_execute(&format!("PRAGMA user_version={version}"))
        .unwrap();
}

#[test]
fn legacy_reset_is_once_only_and_does_not_touch_app_or_other_files() {
    block_on(async {
        for version in [1, 9, 10] {
            let directory = tempfile::tempdir().unwrap();
            let path = directory.path().join("agent.db");
            let app = AppDatabase::open(directory.path().join("app.db"))
                .await
                .unwrap();
            let sentinel = directory.path().join("unrelated.pb");
            std::fs::write(&sentinel, b"keep").unwrap();
            old_database(&path, version);
            let db = AgentDatabase::open(&path).await.unwrap();
            assert_eq!(
                db.query(Q::Conversations).await.unwrap(),
                S::Conversations(vec![])
            );
            let run = claim(&db, "new").await;
            db.write(vec![insert(
                "new",
                &run,
                vec![new_message("m", "User", i64::MAX)],
            )])
            .await
            .unwrap();
            drop(db);
            let reopened = AgentDatabase::open(&path).await.unwrap();
            let value = history(&reopened, "new").await.unwrap();
            assert_eq!(value.messages[0].created_at, i64::MAX);
            assert!(
                value.conversation.is_running,
                "opening must not reset an existing run"
            );
            assert_eq!(std::fs::read(&sentinel).unwrap(), b"keep");
            assert_eq!(
                app.query(AppQuery::Accounts).await.unwrap(),
                flare_database::AppSnapshot::Accounts(vec![])
            );
            assert!(matches!(
                AgentDatabase::open(directory.path().join("app.db")).await,
                Err(Error::Schema(_))
            ));
            assert!(matches!(
                AppDatabase::open(&path).await,
                Err(Error::Schema(_))
            ));
        }
    });
}

#[test]
fn unknown_corrupt_and_future_databases_are_preserved() {
    for setup in [
        "PRAGMA user_version=11",
        "PRAGMA application_id=0x41474e54; PRAGMA user_version=2",
        "CREATE TABLE unrelated(value TEXT)",
    ] {
        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join("agent.db");
        old_database(&path, 10);
        let mut connection = diesel::SqliteConnection::establish(path.to_str().unwrap()).unwrap();
        connection.batch_execute(setup).unwrap();
        drop(connection);
        let before = std::fs::read(&path).unwrap();
        assert!(block_on(AgentDatabase::open(&path)).is_err());
        assert_eq!(std::fs::read(&path).unwrap(), before);
    }
    let directory = tempfile::tempdir().unwrap();
    let path = directory.path().join("agent.db");
    std::fs::write(&path, b"corrupt file").unwrap();
    assert!(block_on(AgentDatabase::open(&path)).is_err());
    assert_eq!(std::fs::read(path).unwrap(), b"corrupt file");
}

#[test]
fn checkpoint_batches_are_atomic_deduplicated_and_consistent() {
    block_on(async {
        let directory = tempfile::tempdir().unwrap();
        let db = AgentDatabase::open(directory.path().join("agent.db"))
            .await
            .unwrap();
        let mut stream = db.observe(Q::History("c".into())).await.unwrap();
        assert_eq!(*stream.next().await.unwrap().unwrap(), S::History(None));
        let run = claim(&db, "c").await;
        stream.next().await.unwrap().unwrap();
        let rows = vec![
            new_message("one", "User", 1),
            new_message("two", "Assistant", 2),
        ];
        let checkpoint = |id: &str| M::SetContext {
            conversation_id: "c".into(),
            run_id: run.clone(),
            through_message_id: id.into(),
            summary: "private summary".into(),
        };
        assert!(
            db.write(vec![insert("c", &run, rows.clone()), checkpoint("missing")])
                .await
                .is_err()
        );
        assert!(history(&db, "c").await.unwrap().messages.is_empty());
        assert!(stream.next().now_or_never().is_none());
        db.write(vec![insert("c", &run, rows.clone()), checkpoint("two")])
            .await
            .unwrap();
        let S::History(Some(value)) = stream.next().await.unwrap().unwrap().as_ref().clone() else {
            panic!()
        };
        assert_eq!(value.context.unwrap().through_position, 1);
        assert_eq!(value.messages.len(), 2);
        assert_eq!(value.messages[0].content_json, rows[0].content_json);
        db.write(vec![insert("c", &run, rows), checkpoint("two")])
            .await
            .unwrap();
        assert!(stream.next().now_or_never().is_none());
        assert!(
            db.write(vec![
                insert("c", &run, vec![new_message("rollback", "User", 3)]),
                checkpoint("one")
            ])
            .await
            .is_err()
        );
        assert_eq!(history(&db, "c").await.unwrap().messages.len(), 2);
        assert!(stream.next().now_or_never().is_none());
    });
}

#[test]
fn positions_ids_and_list_activity_keep_their_semantics() {
    block_on(async {
        let directory = tempfile::tempdir().unwrap();
        let db = AgentDatabase::open(directory.path().join("agent.db"))
            .await
            .unwrap();
        let run = claim(&db, "a").await;
        db.write(vec![insert(
            "a",
            &run,
            vec![new_message("user", "User", 10)],
        )])
        .await
        .unwrap();
        db.write(vec![M::InsertMessages {
            conversation_id: "a".into(),
            run_id: run.clone(),
            placement: agent::Placement::Prepend,
            messages: vec![
                new_message("sys1", "System", 999),
                new_message("sys2", "System", 999),
            ],
        }])
        .await
        .unwrap();
        let positions = db
            .write(vec![insert(
                "a",
                &run,
                vec![
                    new_message("user", "User", 10),
                    new_message("reply", "Assistant", 30),
                ],
            )])
            .await
            .unwrap()
            .messages;
        assert_eq!(
            positions.iter().map(|row| row.position).collect::<Vec<_>>(),
            [0, 1]
        );
        let value = history(&db, "a").await.unwrap();
        assert_eq!(
            value
                .messages
                .iter()
                .map(|row| row.position)
                .collect::<Vec<_>>(),
            [-2, -1, 0, 1]
        );
        let S::Messages(desc) = db
            .query(Q::Messages {
                conversation_id: "a".into(),
                descending: true,
            })
            .await
            .unwrap()
        else {
            panic!()
        };
        assert_eq!(desc[0].message_id, "reply");
        db.write(vec![M::EnsureConversation {
            conversation_id: "b".into(),
            title: None,
            now: 50,
        }])
        .await
        .unwrap();
        let S::Conversations(list) = db.query(Q::Conversations).await.unwrap() else {
            panic!()
        };
        assert_eq!(
            list.iter()
                .map(|row| row.conversation_id.as_str())
                .collect::<Vec<_>>(),
            ["b", "a"]
        );
        assert_eq!(list[1].updated_at, 30);
        assert_eq!(
            db.query(Q::LatestVisibleMessageCreatedAt("a".into()))
                .await
                .unwrap(),
            S::Timestamp(Some(30))
        );
    });
}

#[test]
fn stale_edits_and_late_results_cannot_overwrite_or_resurrect_a_conversation() {
    block_on(async {
        let directory = tempfile::tempdir().unwrap();
        let db = AgentDatabase::open(directory.path().join("agent.db"))
            .await
            .unwrap();
        let old = claim(&db, "c").await;
        let row = new_message("m", "Assistant", 1);
        db.write(vec![insert("c", &old, vec![row.clone()])])
            .await
            .unwrap();
        let edit = M::UpdateMessage {
            conversation_id: "c".into(),
            run_id: old.clone(),
            message_id: "m".into(),
            expected_revision: 0,
            text: "selected".into(),
            content_json: "{\"selected\":true}".into(),
            message_json: row.message_json.clone(),
        };
        db.write(vec![edit.clone()]).await.unwrap();
        assert_eq!(db.write(vec![edit]).await.unwrap_err(), Error::Conflict);
        assert_eq!(
            db.write(vec![insert("c", &old, vec![row.clone()])])
                .await
                .unwrap_err(),
            Error::Conflict
        );
        db.write(vec![M::ClearHistory {
            conversation_id: "c".into(),
            run_id: None,
        }])
        .await
        .unwrap();
        assert!(!history(&db, "c").await.unwrap().conversation.is_running);
        assert_eq!(
            db.write(vec![insert("c", &old, vec![row.clone()])])
                .await
                .unwrap_err(),
            Error::StaleRun
        );
        let current = claim(&db, "c").await;
        assert_ne!(current, old);
        db.write(vec![
            insert("c", &current, vec![row.clone()]),
            M::ClearHistory {
                conversation_id: "c".into(),
                run_id: Some(current.clone()),
            },
        ])
        .await
        .unwrap();
        assert!(history(&db, "c").await.unwrap().conversation.is_running);
        db.write(vec![
            insert("c", &current, vec![row.clone()]),
            M::DeleteConversation("c".into()),
        ])
        .await
        .unwrap();
        assert_eq!(
            db.write(vec![insert("c", &current, vec![row])])
                .await
                .unwrap_err(),
            Error::StaleRun
        );
        assert!(history(&db, "c").await.is_none());
    });
}

#[test]
fn concurrent_claims_are_exclusive_and_message_positions_are_allocated_atomically() {
    block_on(async {
        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join("agent.db");
        let handles = futures::future::try_join_all((0..16).map(|_| AgentDatabase::open(&path)))
            .await
            .unwrap();
        let claims = join_all(handles.iter().map(|db| {
            db.write(vec![M::ClaimRun {
                conversation_id: "c".into(),
                now: 1,
            }])
        }))
        .await;
        assert_eq!(claims.iter().filter(|value| value.is_ok()).count(), 1);
        assert_eq!(
            claims
                .iter()
                .filter(|value| matches!(value, Err(Error::RunBusy)))
                .count(),
            15
        );
        let run = claims
            .into_iter()
            .find_map(Result::ok)
            .unwrap()
            .runs
            .remove(0)
            .run_id;
        let writes = join_all(handles.iter().enumerate().map(|(i, db)| {
            db.write(vec![insert(
                "c",
                &run,
                vec![new_message(&i.to_string(), "User", 1)],
            )])
        }))
        .await;
        assert!(writes.iter().all(Result::is_ok));
        let value = history(&handles[0], "c").await.unwrap();
        assert_eq!(
            value
                .messages
                .iter()
                .map(|row| row.position)
                .collect::<Vec<_>>(),
            (0..16).collect::<Vec<_>>()
        );
    });
}

#[test]
fn retry_consumption_survives_reopen_without_regranting_the_old_boundary() {
    block_on(async {
        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join("agent.db");
        let db = AgentDatabase::open(&path).await.unwrap();
        let run = claim(&db, "c").await;
        db.write(vec![
            insert("c", &run, vec![new_message("m", "User", 1)]),
            M::SetContext {
                conversation_id: "c".into(),
                run_id: run.clone(),
                through_message_id: "m".into(),
                summary: "s".into(),
            },
            M::FinishRun {
                conversation_id: "c".into(),
                run_id: run,
                error_message: Some("retryable".into()),
                retry_json: Some("{\"mode\":\"resume\"}".into()),
            },
        ])
        .await
        .unwrap();
        let consumed = db
            .write(vec![M::ConsumeRetry {
                conversation_id: "c".into(),
                now: 2,
            }])
            .await
            .unwrap()
            .runs
            .remove(0);
        assert_eq!(
            consumed.retry_json.as_deref(),
            Some("{\"mode\":\"resume\"}")
        );
        drop(db);
        let db = AgentDatabase::open(&path).await.unwrap();
        assert_eq!(
            db.write(vec![M::ConsumeRetry {
                conversation_id: "c".into(),
                now: 3
            }])
            .await
            .unwrap_err(),
            Error::RunBusy
        );
        db.write(vec![M::FinishRun {
            conversation_id: "c".into(),
            run_id: consumed.run_id,
            error_message: Some("interrupted".into()),
            retry_json: None,
        }])
        .await
        .unwrap();
        assert_eq!(
            db.write(vec![M::ConsumeRetry {
                conversation_id: "c".into(),
                now: 3
            }])
            .await
            .unwrap_err(),
            Error::Conflict
        );
        let value = history(&db, "c").await.unwrap();
        assert_eq!(value.messages.len(), 1);
        assert!(value.context.is_some());
    });
}

#[test]
fn title_guards_do_not_replace_generated_titles_or_revive_deleted_rows() {
    block_on(async {
        let directory = tempfile::tempdir().unwrap();
        let db = AgentDatabase::open(directory.path().join("agent.db"))
            .await
            .unwrap();
        let run = claim(&db, "c").await;
        db.write(vec![
            M::SetTitle {
                conversation_id: "c".into(),
                run_id: None,
                title: "fallback".into(),
                generated: false,
            },
            M::FinishRun {
                conversation_id: "c".into(),
                run_id: run.clone(),
                error_message: None,
                retry_json: None,
            },
            M::SetTitle {
                conversation_id: "c".into(),
                run_id: Some(run.clone()),
                title: "generated".into(),
                generated: true,
            },
        ])
        .await
        .unwrap();
        db.write(vec![M::SetTitle {
            conversation_id: "c".into(),
            run_id: None,
            title: "must-not-replace".into(),
            generated: false,
        }])
        .await
        .unwrap();
        assert_eq!(
            history(&db, "c")
                .await
                .unwrap()
                .conversation
                .title
                .as_deref(),
            Some("generated")
        );
        claim(&db, "c").await;
        assert_eq!(
            db.write(vec![M::SetTitle {
                conversation_id: "c".into(),
                run_id: Some(run),
                title: "stale".into(),
                generated: true
            }])
            .await
            .unwrap_err(),
            Error::StaleRun
        );
    });
}

#[test]
fn opaque_json_is_preserved_and_invalid_payloads_do_not_publish_or_leak() {
    block_on(async {
        let directory = tempfile::tempdir().unwrap();
        let db = AgentDatabase::open(directory.path().join("agent.db"))
            .await
            .unwrap();
        let run = claim(&db, "c").await;
        let mut row = new_message("m", "User", 1);
        row.content_json = "[1e400,{\"secret\":\"private-payload\"}]".into();
        db.write(vec![insert("c", &run, vec![row.clone()])])
            .await
            .unwrap();
        assert_eq!(
            history(&db, "c").await.unwrap().messages[0].content_json,
            row.content_json
        );
        assert!(!format!("{row:?}").contains("private-payload"));
        assert!(
            !format!("{:?}", db.query(Q::History("c".into())).await.unwrap())
                .contains("private-payload")
        );
        let mut changes = db.observe(Q::History("c".into())).await.unwrap();
        changes.next().await.unwrap().unwrap();
        row.message_id = "bad".into();
        row.content_json = "{not-json-private-payload".into();
        let error = db
            .write(vec![insert("c", &run, vec![row])])
            .await
            .unwrap_err();
        assert!(matches!(error, Error::InvalidRequest(_)));
        assert!(!error.to_string().contains("private-payload"));
        assert!(changes.next().now_or_never().is_none());
        assert_eq!(history(&db, "c").await.unwrap().messages.len(), 1);
    });
}
