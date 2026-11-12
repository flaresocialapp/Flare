use crate::{
    Error,
    migration::{quoted, table, table_names},
};
use diesel::{
    connection::SimpleConnection,
    prelude::*,
    sql_types::{Integer, Text},
};
use std::collections::BTreeSet;

pub(crate) const VERSION: i32 = 4;
const APPLICATION_ID: i32 = 0x4341_4348;
pub(super) const TABLES: [&str; 18] = [
    "cache_statuses",
    "cache_references",
    "cache_timelines",
    "cache_presentations",
    "cache_paging_keys",
    "cache_users",
    "cache_relations",
    "cache_user_history",
    "cache_emojis",
    "cache_emoji_history",
    "cache_lists",
    "cache_list_pages",
    "cache_list_members",
    "cache_room_timelines",
    "cache_rooms",
    "cache_room_members",
    "cache_messages",
    "cache_translations",
];
const LEGACY: [&str; 20] = [
    "DbStatus",
    "status_reference",
    "DbPagingTimeline",
    "timeline_item_presentation_reference",
    "DbPagingKey",
    "DbUser",
    "DbUserRelation",
    "DbUserHistory",
    "DbEmoji",
    "DbEmojiHistory",
    "DbList",
    "DbListPaging",
    "DbListMember",
    "DbDirectMessageTimeline",
    "DbMessageRoom",
    "DbMessageRoomReference",
    "DbMessageItem",
    "DbTranslation",
    "room_master_table",
    "android_metadata",
];
// Keep v1's layout so a clearing upgrade validates the source before dropping anything.
const DDL: &str = r"
CREATE TABLE cache_meta(id INTEGER NOT NULL PRIMARY KEY CHECK(id=1),epoch TEXT NOT NULL);
INSERT INTO cache_meta VALUES(1,lower(hex(randomblob(16))));
CREATE TABLE cache_changes(topic TEXT NOT NULL,entry TEXT NOT NULL,structure INTEGER NOT NULL,PRIMARY KEY(topic,entry));
CREATE TABLE cache_statuses(id TEXT NOT NULL PRIMARY KEY,account TEXT NOT NULL,status_key TEXT NOT NULL,payload TEXT NOT NULL,fingerprint INTEGER NOT NULL,render_hash INTEGER NOT NULL,text TEXT,UNIQUE(account,status_key));
CREATE TABLE cache_references(status_id TEXT NOT NULL REFERENCES cache_statuses(id) ON DELETE CASCADE,reference_type TEXT NOT NULL,reference_status_id TEXT NOT NULL,reference_order INTEGER NOT NULL,PRIMARY KEY(status_id,reference_type,reference_status_id));
CREATE INDEX cache_reference_target ON cache_references(reference_status_id);
CREATE TABLE cache_paging_keys(paging_key TEXT NOT NULL PRIMARY KEY,generation TEXT NOT NULL,revision INTEGER NOT NULL CHECK(revision>=0),next_key TEXT,prev_key TEXT,source_heads TEXT NOT NULL,account_keys TEXT NOT NULL);
CREATE TABLE cache_timelines(paging_key TEXT NOT NULL REFERENCES cache_paging_keys(paging_key) ON DELETE CASCADE,timeline_id TEXT NOT NULL,status_id TEXT NOT NULL REFERENCES cache_statuses(id) ON DELETE CASCADE,sort_id INTEGER NOT NULL,is_gap INTEGER NOT NULL,message TEXT,message_render_hash INTEGER,notification_key TEXT,content_revision INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(paging_key,timeline_id));
CREATE INDEX cache_timeline_page ON cache_timelines(paging_key,sort_id,timeline_id);
CREATE INDEX cache_timeline_status ON cache_timelines(status_id);
CREATE TABLE cache_presentations(paging_key TEXT NOT NULL,timeline_id TEXT NOT NULL,presentation_type TEXT NOT NULL,reference_status_id TEXT NOT NULL,reference_order INTEGER NOT NULL,PRIMARY KEY(paging_key,timeline_id,presentation_type,reference_status_id),FOREIGN KEY(paging_key,timeline_id) REFERENCES cache_timelines(paging_key,timeline_id) ON DELETE CASCADE);
CREATE INDEX cache_presentation_target ON cache_presentations(reference_status_id);
CREATE TABLE cache_users(user_key TEXT NOT NULL PRIMARY KEY,name TEXT NOT NULL,canonical_handle TEXT NOT NULL,host TEXT NOT NULL,payload TEXT NOT NULL);
CREATE INDEX cache_user_handle ON cache_users(host,canonical_handle);
CREATE TABLE cache_relations(account TEXT NOT NULL,user_key TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(account,user_key));
CREATE TABLE cache_user_history(account TEXT NOT NULL,user_key TEXT NOT NULL,last_visit INTEGER NOT NULL,PRIMARY KEY(account,user_key));
CREATE INDEX cache_history_order ON cache_user_history(account,last_visit,user_key);
CREATE TABLE cache_emojis(host TEXT NOT NULL PRIMARY KEY,payload TEXT NOT NULL);
CREATE TABLE cache_emoji_history(account TEXT NOT NULL,short_code TEXT NOT NULL,last_use INTEGER NOT NULL,PRIMARY KEY(account,short_code));
CREATE TABLE cache_lists(id TEXT NOT NULL PRIMARY KEY,account TEXT NOT NULL,list_key TEXT NOT NULL,payload TEXT NOT NULL,UNIQUE(account,list_key));
CREATE TABLE cache_list_pages(paging_key TEXT NOT NULL,account TEXT NOT NULL,list_key TEXT NOT NULL,position INTEGER NOT NULL,PRIMARY KEY(paging_key,account,list_key));
CREATE INDEX cache_list_page_order ON cache_list_pages(paging_key,position,list_key);
CREATE TABLE cache_list_members(list_key TEXT NOT NULL,member_key TEXT NOT NULL,PRIMARY KEY(list_key,member_key));
CREATE INDEX cache_member_user ON cache_list_members(member_key);
CREATE TABLE cache_room_timelines(account TEXT NOT NULL,room_key TEXT NOT NULL,sort_id INTEGER NOT NULL,unread_count INTEGER NOT NULL CHECK(unread_count>=0),payload TEXT NOT NULL,PRIMARY KEY(account,room_key));
CREATE INDEX cache_room_order ON cache_room_timelines(account,sort_id,room_key);
CREATE TABLE cache_rooms(room_key TEXT NOT NULL PRIMARY KEY,platform_id TEXT NOT NULL,last_message_key TEXT);
CREATE TABLE cache_room_members(room_key TEXT NOT NULL,user_key TEXT NOT NULL,PRIMARY KEY(room_key,user_key));
CREATE TABLE cache_messages(message_key TEXT NOT NULL PRIMARY KEY,room_key TEXT NOT NULL,user_key TEXT NOT NULL,timestamp INTEGER NOT NULL,payload TEXT NOT NULL,show_sender INTEGER NOT NULL,is_local INTEGER NOT NULL,remote_cursor TEXT,revision INTEGER NOT NULL CHECK(revision>=0));
CREATE INDEX cache_message_page ON cache_messages(room_key,timestamp,message_key);
CREATE TABLE cache_translations(id TEXT NOT NULL PRIMARY KEY,entity_type TEXT NOT NULL,entity_key TEXT NOT NULL,target_language TEXT NOT NULL,source_hash TEXT NOT NULL,status TEXT NOT NULL,display_mode TEXT NOT NULL,payload TEXT,status_reason TEXT,attempt_count INTEGER NOT NULL CHECK(attempt_count>=0),updated_at INTEGER NOT NULL,UNIQUE(entity_type,entity_key,target_language));
CREATE INDEX cache_translation_state ON cache_translations(status,updated_at);
";

#[derive(QueryableByName)]
struct Metadata {
    #[diesel(sql_type=Integer)]
    user_version: i32,
    #[diesel(sql_type=Integer)]
    application_id: i32,
}
#[derive(QueryableByName, PartialEq, Eq)]
struct Object {
    #[diesel(sql_type=Text)]
    name: String,
    #[diesel(sql_type=Text)]
    tbl_name: String,
    #[diesel(sql_type=Text)]
    sql: String,
}
fn objects(c: &mut SqliteConnection) -> Result<Vec<Object>, Error> {
    Ok(diesel::sql_query("SELECT name,tbl_name,sql FROM sqlite_master WHERE type IN ('trigger','view') ORDER BY name").load(c)?)
}
pub(crate) fn initialize(c: &mut SqliteConnection) -> Result<(), Error> {
    let meta = diesel::sql_query(
        "SELECT user_version,application_id FROM pragma_user_version,pragma_application_id",
    )
    .get_result::<Metadata>(c)?;
    let names = table_names(c)?;
    if meta.application_id == APPLICATION_ID && meta.user_version == VERSION {
        return validate(c, VERSION);
    }
    if meta.application_id == APPLICATION_ID && meta.user_version == 3 {
        validate(c, 3)?;
        return c.immediate_transaction::<_, Error, _>(|c| {
            indexes(c)?;
            validate(c, VERSION)?;
            c.batch_execute(&format!("PRAGMA user_version={VERSION}"))?;
            Ok(())
        });
    }
    let objects = objects(c)?;
    let fresh = meta.application_id == 0
        && meta.user_version == 0
        && names.is_empty()
        && objects.is_empty();
    let old_rust = meta.application_id == APPLICATION_ID && (1..=2).contains(&meta.user_version);
    if old_rust {
        validate(c, meta.user_version)?;
    }
    let legacy = meta.application_id == 0
        && (1..=53).contains(&meta.user_version)
        && [
            "room_master_table",
            "DbStatus",
            "DbPagingTimeline",
            "DbUser",
        ]
        .iter()
        .all(|name| names.contains(*name));
    if !fresh && !legacy && !old_rust {
        return Err(Error::UnsupportedVersion(meta.user_version));
    }
    if legacy
        && (names.iter().any(|n| !LEGACY.contains(&n.as_str()))
            || objects.iter().any(|o| {
                !LEGACY.contains(&o.tbl_name.as_str()) || !o.sql.starts_with("CREATE TRIGGER")
            }))
    {
        return Err(Error::Schema("unrecognized legacy cache objects".into()));
    }
    rebuild(c, &objects, &names, old_rust)
}
fn rebuild(
    c: &mut SqliteConnection,
    objects: &[Object],
    names: &BTreeSet<String>,
    old_rust: bool,
) -> Result<(), Error> {
    c.immediate_transaction::<_, Error, _>(|c| {
        for o in objects {
            c.batch_execute(&format!("DROP TRIGGER {}", quoted(&o.name)))?;
        }
        if old_rust {
            for name in [
                "cache_presentations",
                "cache_timelines",
                "cache_references",
                "cache_paging_keys",
                "cache_statuses",
                "cache_users",
                "cache_relations",
                "cache_user_history",
                "cache_emojis",
                "cache_emoji_history",
                "cache_lists",
                "cache_list_pages",
                "cache_list_members",
                "cache_room_timelines",
                "cache_rooms",
                "cache_room_members",
                "cache_messages",
                "cache_translations",
                "cache_changes",
                "cache_meta",
            ] {
                c.batch_execute(&format!("DROP TABLE {}", quoted(name)))?;
            }
        }
        // Children first; no directory deletion and no app/Agent tables are accepted here.
        for name in [
            "timeline_item_presentation_reference",
            "status_reference",
            "DbPagingTimeline",
            "DbMessageRoomReference",
            "DbListMember",
            "DbListPaging",
            "DbUserRelation",
            "DbUserHistory",
            "DbEmojiHistory",
            "DbDirectMessageTimeline",
            "DbMessageItem",
            "DbTranslation",
            "DbMessageRoom",
            "DbList",
            "DbEmoji",
            "DbPagingKey",
            "DbUser",
            "DbStatus",
            "room_master_table",
            "android_metadata",
        ] {
            if names.contains(name) {
                c.batch_execute(&format!("DROP TABLE {}", quoted(name)))?;
            }
        }
        create(c, VERSION)?;
        validate(c, VERSION)?;
        c.batch_execute(&format!(
            "PRAGMA user_version={VERSION}; PRAGMA application_id={APPLICATION_ID}"
        ))?;
        Ok(())
    })
}
fn create(c: &mut SqliteConnection, version: i32) -> Result<(), Error> {
    c.batch_execute(DDL)?;
    if version >= 2 {
        c.batch_execute("ALTER TABLE cache_statuses ADD COLUMN version TEXT NOT NULL DEFAULT ''")?;
    }
    if version >= 3 {
        c.batch_execute("DROP INDEX cache_reference_target; CREATE INDEX cache_reference_target ON cache_references(reference_status_id,status_id);
            DROP INDEX cache_presentation_target; CREATE INDEX cache_presentation_target ON cache_presentations(reference_status_id,presentation_type,paging_key,timeline_id);")?;
        if version >= 4 {
            indexes(c)?;
        }
        super::triggers::install(c)
    } else {
        super::legacy_triggers::install(c)
    }
}
fn indexes(c: &mut SqliteConnection) -> Result<(), Error> {
    c.batch_execute(
        "CREATE INDEX cache_room_by_key ON cache_room_timelines(room_key);
        CREATE INDEX cache_status_account ON cache_statuses(account,id);
        DROP INDEX cache_timeline_status;
        CREATE INDEX cache_timeline_status ON cache_timelines(status_id,paging_key,timeline_id);",
    )?;
    Ok(())
}
fn validate(c: &mut SqliteConnection, version: i32) -> Result<(), Error> {
    let mut expected: std::collections::BTreeSet<String> =
        TABLES.into_iter().map(str::to_owned).collect();
    expected.extend(["cache_meta".into(), "cache_changes".into()]);
    if table_names(c)? != expected {
        return Err(Error::Schema("unexpected cache tables".into()));
    }
    let mut reference = SqliteConnection::establish(":memory:")?;
    create(&mut reference, version)?;
    for name in expected {
        if table(c, &name)? != table(&mut reference, &name)? {
            return Err(Error::Schema(format!("cache layout of {name}")));
        }
    }
    if objects(c)? != objects(&mut reference)? {
        return Err(Error::Schema("cache triggers differ".into()));
    }
    if diesel::sql_query(
        "SELECT COUNT(*) AS user_version,0 AS application_id FROM pragma_foreign_key_check",
    )
    .get_result::<Metadata>(c)?
    .user_version
        != 0
    {
        return Err(Error::Schema("cache foreign key violations".into()));
    }
    if diesel::sql_query("SELECT COUNT(*) AS user_version,0 AS application_id FROM cache_meta WHERE id=1 AND length(epoch)=32").get_result::<Metadata>(c)?.user_version!=1 {
  return Err(Error::Schema("cache epoch missing".into()));
 }
    Ok(())
}

#[cfg(all(test, not(target_arch = "wasm32")))]
mod tests {
    use super::*;
    fn old_rust_at(url: &str, version: i32) -> SqliteConnection {
        let mut c = SqliteConnection::establish(url).unwrap();
        c.batch_execute("PRAGMA foreign_keys=ON").unwrap();
        create(&mut c, version).unwrap();
        c.batch_execute(&format!("PRAGMA user_version={version};PRAGMA application_id={APPLICATION_ID};
            INSERT INTO cache_statuses(id,account,status_key,payload,fingerprint,render_hash,text) VALUES('s','a','s','{{}}',1,1,'search');
            INSERT INTO cache_paging_keys VALUES('home','generation',4,NULL,NULL,'{{}}','[\"a\"]');
            INSERT INTO cache_timelines VALUES('home','t','s',1,0,NULL,NULL,NULL,0);
            INSERT INTO cache_references VALUES('s','Quote','missing',0);
            INSERT INTO cache_presentations VALUES('home','t','Quote','missing',0);
            INSERT INTO cache_users VALUES('u','name','handle','host','{{}}');
            INSERT INTO cache_relations VALUES('a','u','{{}}');
            INSERT INTO cache_user_history VALUES('a','u',1);
            INSERT INTO cache_emojis VALUES('host','[]');
            INSERT INTO cache_emoji_history VALUES('a','smile',1);
            INSERT INTO cache_lists VALUES('l','a','l','{{}}');
            INSERT INTO cache_list_pages VALUES('lists','a','l',1);
            INSERT INTO cache_list_members VALUES('l','u');
            INSERT INTO cache_room_timelines VALUES('a','r',1,3,'{{}}');
            INSERT INTO cache_rooms VALUES('r','remote','m');
            INSERT INTO cache_room_members VALUES('r','u');
            INSERT INTO cache_messages VALUES('m','r','u',1,'{{}}',0,1,NULL,0);
            INSERT INTO cache_translations VALUES('tr','Status','s','zh','hash','Pending','Auto','{{}}',NULL,1,1);
            DELETE FROM cache_changes;")).unwrap();
        if version >= 2 {
            c.batch_execute("UPDATE cache_statuses SET version=lower(hex(randomblob(16)));DELETE FROM cache_changes;").unwrap();
        }
        c
    }
    #[test]
    fn v3_index_upgrade_preserves_every_entity_epoch_and_status_version() {
        use crate::{
            CacheDatabase,
            cache::{Query, Snapshot},
        };
        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join("cache.db");
        let mut source = old_rust_at(path.to_str().unwrap(), 3);
        let before = super::super::engine::read(&mut source, &Query::Stats).unwrap();
        let info = super::super::engine::read(&mut source, &Query::Info).unwrap();
        let status = super::super::engine::read(&mut source, &Query::Status("s".into())).unwrap();
        drop(source);
        futures::executor::block_on(async {
            let db = CacheDatabase::open(&path).await.unwrap();
            assert_eq!(db.query(Query::Stats).await.unwrap(), before);
            assert_eq!(db.query(Query::Info).await.unwrap(), info);
            assert_eq!(db.query(Query::Status("s".into())).await.unwrap(), status);
            let Snapshot::Stats(counts) = db.query(Query::Stats).await.unwrap() else {
                panic!()
            };
            assert!(counts.iter().all(|(_, n)| *n == 1));
            drop(db);
            let reopened = CacheDatabase::open(&path).await.unwrap();
            assert_eq!(reopened.query(Query::Stats).await.unwrap(), before);
            assert_eq!(reopened.query(Query::Info).await.unwrap(), info);
        });
        let mut upgraded = SqliteConnection::establish(path.to_str().unwrap()).unwrap();
        validate(&mut upgraded, VERSION).unwrap();
        assert_eq!(
            diesel::sql_query(
                "SELECT user_version,application_id FROM pragma_user_version,pragma_application_id"
            )
            .get_result::<Metadata>(&mut upgraded)
            .unwrap()
            .user_version,
            VERSION
        );
    }
    #[test]
    fn failed_v3_index_upgrade_preserves_the_original_file() {
        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join("cache.db");
        let mut c = old_rust_at(path.to_str().unwrap(), 3);
        let info = super::super::engine::read(&mut c, &super::super::Query::Info).unwrap();
        let pages = diesel::sql_query(
            "SELECT page_count AS user_version,0 AS application_id FROM pragma_page_count",
        )
        .get_result::<Metadata>(&mut c)
        .unwrap()
        .user_version;
        c.batch_execute(&format!("PRAGMA max_page_count={}", pages + 1))
            .unwrap();
        assert!(initialize(&mut c).is_err());
        drop(c);
        let mut c = SqliteConnection::establish(path.to_str().unwrap()).unwrap();
        validate(&mut c, 3).unwrap();
        assert_eq!(
            info,
            super::super::engine::read(&mut c, &super::super::Query::Info).unwrap()
        );
        let super::super::Snapshot::Stats(counts) =
            super::super::engine::read(&mut c, &super::super::Query::Stats).unwrap()
        else {
            panic!()
        };
        assert!(counts.iter().all(|(_, n)| *n == 1));
        assert_eq!(
            diesel::sql_query(
                "SELECT user_version,application_id FROM pragma_user_version,pragma_application_id"
            )
            .get_result::<Metadata>(&mut c)
            .unwrap()
            .user_version,
            3
        );
        initialize(&mut c).unwrap();
        validate(&mut c, VERSION).unwrap();
    }
    #[test]
    fn opening_old_rust_files_upgrades_and_rejects_the_previous_epoch() {
        use crate::{
            CacheDatabase,
            cache::{Emoji, Mutation, Query, Record, Snapshot},
        };
        for version in [1, 2] {
            let directory = tempfile::tempdir().unwrap();
            let path = directory.path().join("cache.db");
            let mut old = old_rust_at(path.to_str().unwrap(), version);
            let Snapshot::Info(info) = super::super::engine::read(&mut old, &Query::Info).unwrap()
            else {
                panic!()
            };
            drop(old);
            futures::executor::block_on(async {
                let database = CacheDatabase::open(&path).await.unwrap();
                let Snapshot::Info(new) = database.query(Query::Info).await.unwrap() else {
                    panic!()
                };
                assert_ne!(info.epoch, new.epoch);
                assert_eq!(
                    database
                        .write(
                            &info.epoch,
                            vec![Mutation::Put(Record::Emoji(Emoji {
                                host: "late".into(),
                                payload: "[]".into()
                            }))]
                        )
                        .await
                        .unwrap_err(),
                    Error::Conflict
                );
                database
                    .write(
                        &new.epoch,
                        vec![Mutation::Put(Record::Emoji(Emoji {
                            host: "new".into(),
                            payload: "[]".into(),
                        }))],
                    )
                    .await
                    .unwrap();
                drop(database);
                let reopened = CacheDatabase::open(&path).await.unwrap();
                assert_eq!(
                    reopened.query(Query::Info).await.unwrap(),
                    Snapshot::Info(new)
                );
                let Snapshot::Records(rows) =
                    reopened.query(Query::Emoji("new".into())).await.unwrap()
                else {
                    panic!()
                };
                assert_eq!(rows.len(), 1);
            });
        }
    }
    #[test]
    fn old_rust_upgrade_clears_every_entity_once_and_changes_epoch() {
        for version in [1, 2] {
            let mut c = old_rust_at(":memory:", version);
            validate(&mut c, version).unwrap();
            let old = super::super::engine::read(&mut c, &super::super::Query::Info).unwrap();
            initialize(&mut c).unwrap();
            validate(&mut c, VERSION).unwrap();
            let new = super::super::engine::read(&mut c, &super::super::Query::Info).unwrap();
            assert_ne!(old, new);
            let super::super::Snapshot::Stats(counts) =
                super::super::engine::read(&mut c, &super::super::Query::Stats).unwrap()
            else {
                panic!()
            };
            assert!(counts.iter().all(|(_, n)| *n == 0));
            c.batch_execute("INSERT INTO cache_emojis VALUES('new','[]')")
                .unwrap();
            initialize(&mut c).unwrap();
            assert_eq!(
                new,
                super::super::engine::read(&mut c, &super::super::Query::Info).unwrap()
            );
            assert_eq!(
                diesel::sql_query(
                    "SELECT COUNT(*) AS user_version,0 AS application_id FROM cache_emojis"
                )
                .get_result::<Metadata>(&mut c)
                .unwrap()
                .user_version,
                1
            );
        }
    }
    #[test]
    fn failed_rust_upgrade_restores_data_triggers_epoch_and_version() {
        for version in [1, 2] {
            let mut c = old_rust_at(":memory:", version);
            validate(&mut c, version).unwrap();
            let names = table_names(&mut c).unwrap();
            let triggers = objects(&mut c).unwrap();
            let epoch = super::super::engine::read(&mut c, &super::super::Query::Info).unwrap();
            // Introduce a failure after validation, at the last DROP, to exercise the transaction.
            c.batch_execute("CREATE TEMP VIEW cache_meta AS SELECT 1")
                .unwrap();
            assert!(rebuild(&mut c, &triggers, &names, true).is_err());
            c.batch_execute("DROP VIEW temp.cache_meta").unwrap();
            validate(&mut c, version).unwrap();
            assert_eq!(
                epoch,
                super::super::engine::read(&mut c, &super::super::Query::Info).unwrap()
            );
            assert_eq!(objects(&mut c).unwrap().len(), triggers.len());
            assert_eq!(
            diesel::sql_query(
                "SELECT user_version,application_id FROM pragma_user_version,pragma_application_id"
            )
            .get_result::<Metadata>(&mut c)
            .unwrap()
            .user_version,
            version
        );
            let super::super::Snapshot::Stats(counts) =
                super::super::engine::read(&mut c, &super::super::Query::Stats).unwrap()
            else {
                panic!()
            };
            assert!(counts.iter().all(|(_, n)| *n == 1));
        }
    }
    #[test]
    fn unsupported_or_unknown_old_rust_layout_is_preserved() {
        for version in [1, 2] {
            for sql in [
                "CREATE TABLE unrelated(value TEXT)",
                "DROP TRIGGER cache_statuses_INSERT",
                "PRAGMA user_version=5",
            ] {
                let mut c = old_rust_at(":memory:", version);
                c.batch_execute(sql).unwrap();
                let epoch = super::super::engine::read(&mut c, &super::super::Query::Info).unwrap();
                let names = table_names(&mut c).unwrap();
                assert!(initialize(&mut c).is_err());
                assert_eq!(names, table_names(&mut c).unwrap());
                assert_eq!(
                    epoch,
                    super::super::engine::read(&mut c, &super::super::Query::Info).unwrap()
                );
            }
        }
    }
    #[test]
    fn changed_rust_layout_is_rejected_without_rebuilding() {
        let mut c = SqliteConnection::establish(":memory:").unwrap();
        initialize(&mut c).unwrap();
        let epoch = super::super::engine::read(&mut c, &super::super::Query::Info).unwrap();
        c.batch_execute("DROP TRIGGER cache_statuses_INSERT")
            .unwrap();
        assert!(matches!(initialize(&mut c), Err(Error::Schema(_))));
        assert_eq!(
            epoch,
            super::super::engine::read(&mut c, &super::super::Query::Info).unwrap()
        );
        assert!(
            !objects(&mut c)
                .unwrap()
                .iter()
                .any(|o| o.name == "cache_statuses_INSERT")
        );
    }
    #[test]
    fn rebuild_failure_rolls_back() {
        let mut c = SqliteConnection::establish(":memory:").unwrap();
        c.batch_execute("CREATE TABLE DbStatus(id TEXT); CREATE TABLE DbPagingTimeline(id TEXT); CREATE TABLE DbUser(id TEXT); CREATE TABLE room_master_table(id INTEGER); INSERT INTO DbStatus VALUES('keep'); PRAGMA user_version=53; CREATE TEMP VIEW room_master_table AS SELECT 1;").unwrap();
        let names = table_names(&mut c).unwrap();
        assert!(initialize(&mut c).is_err());
        assert_eq!(names, table_names(&mut c).unwrap());
        assert_eq!(
            diesel::sql_query(
                "SELECT 53 AS user_version,0 AS application_id FROM DbStatus WHERE id='keep'"
            )
            .get_result::<Metadata>(&mut c)
            .unwrap()
            .user_version,
            53
        );
        c.batch_execute("DROP VIEW temp.room_master_table").unwrap();
        initialize(&mut c).unwrap();
        validate(&mut c, VERSION).unwrap();
    }
}
