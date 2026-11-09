use crate::{
    Error,
    migration::{quoted, table, table_names},
};
use diesel::{
    connection::SimpleConnection,
    prelude::*,
    sql_types::{Integer, Text},
};

pub(crate) const VERSION: i32 = 1;
const APPLICATION_ID: i32 = 0x4147_4e54;
const TABLES: [&str; 3] = ["agent_conversations", "agent_messages", "agent_contexts"];
const DDL: &str = "
CREATE TABLE agent_conversations (
 conversation_id TEXT NOT NULL PRIMARY KEY, title TEXT, title_generated INTEGER NOT NULL DEFAULT 0,
 created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, is_running INTEGER NOT NULL DEFAULT 0,
 run_id TEXT, error_message TEXT, retry_json TEXT,
 CHECK (is_running=0 OR run_id IS NOT NULL));
CREATE TABLE agent_messages (
 conversation_id TEXT NOT NULL REFERENCES agent_conversations(conversation_id) ON DELETE CASCADE,
 message_id TEXT NOT NULL, position INTEGER NOT NULL, role TEXT NOT NULL, text TEXT NOT NULL,
 content_json TEXT NOT NULL, message_json TEXT NOT NULL, created_at INTEGER NOT NULL,
 content_revision INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(conversation_id,message_id),
 UNIQUE(conversation_id,position));
CREATE TABLE agent_contexts (
 conversation_id TEXT NOT NULL PRIMARY KEY REFERENCES agent_conversations(conversation_id) ON DELETE CASCADE,
 summary TEXT NOT NULL, through_message_id TEXT NOT NULL, through_position INTEGER NOT NULL,
 FOREIGN KEY(conversation_id,through_message_id) REFERENCES agent_messages(conversation_id,message_id) ON DELETE CASCADE);
";

#[derive(QueryableByName)]
struct Metadata {
    #[diesel(sql_type = Integer)]
    user_version: i32,
    #[diesel(sql_type = Integer)]
    application_id: i32,
}
#[derive(QueryableByName)]
struct Name {
    #[diesel(sql_type = Text)]
    name: String,
}

pub(crate) fn initialize(connection: &mut SqliteConnection) -> Result<(), Error> {
    let meta = diesel::sql_query(
        "SELECT user_version,application_id FROM pragma_user_version,pragma_application_id",
    )
    .get_result::<Metadata>(connection)?;
    let names = table_names(connection)?;
    if !diesel::sql_query("SELECT name FROM sqlite_master WHERE type IN ('trigger','view')")
        .load::<Name>(connection)?
        .is_empty()
    {
        return Err(Error::Schema("unexpected Agent triggers or views".into()));
    }
    if meta.application_id == APPLICATION_ID && meta.user_version == VERSION {
        return validate(connection);
    }
    let fresh = meta.application_id == 0 && meta.user_version == 0 && names.is_empty();
    let old_rust =
        meta.application_id == APPLICATION_ID && (1..VERSION).contains(&meta.user_version);
    let legacy = meta.application_id == 0
        && (1..=10).contains(&meta.user_version)
        && names.contains("room_master_table")
        && names.contains("agent_conversations")
        && names.contains("agent_messages");
    if !fresh && !old_rust && !legacy {
        return Err(Error::UnsupportedVersion(meta.user_version));
    }
    if names.iter().any(|name| {
        !TABLES.contains(&name.as_str())
            && name != "room_master_table"
            && name != "android_metadata"
    }) {
        return Err(Error::Schema("unexpected tables in Agent database".into()));
    }
    if legacy {
        for (name, column) in [
            ("agent_conversations", "conversationId"),
            ("agent_messages", "position"),
        ] {
            let columns = diesel::sql_query(format!(
                "SELECT name FROM pragma_table_info({})",
                quoted(name)
            ))
            .load::<Name>(connection)?;
            if !columns.iter().any(|value| value.name == column) {
                return Err(Error::Schema("unrecognized legacy Agent layout".into()));
            }
        }
    }
    connection.immediate_transaction::<_, Error, _>(|connection| {
        for name in [
            "agent_contexts",
            "agent_messages",
            "agent_conversations",
            "room_master_table",
            "android_metadata",
        ] {
            connection.batch_execute(&format!("DROP TABLE IF EXISTS {}", quoted(name)))?;
        }
        connection.batch_execute(DDL)?;
        validate(connection)?;
        connection.batch_execute(&format!(
            "PRAGMA user_version={VERSION}; PRAGMA application_id={APPLICATION_ID}"
        ))?;
        Ok(())
    })
}

fn validate(connection: &mut SqliteConnection) -> Result<(), Error> {
    if table_names(connection)? != TABLES.into_iter().map(str::to_owned).collect() {
        return Err(Error::Schema("unexpected Agent tables".into()));
    }
    let mut reference = SqliteConnection::establish(":memory:")?;
    reference.batch_execute(DDL)?;
    for name in TABLES {
        if table(connection, name)? != table(&mut reference, name)? {
            return Err(Error::Schema(format!("Agent layout of {name}")));
        }
    }
    if !diesel::sql_query("SELECT \"table\" AS name FROM pragma_foreign_key_check")
        .load::<Name>(connection)?
        .is_empty()
    {
        return Err(Error::Schema("Agent foreign key violations".into()));
    }
    Ok(())
}

#[cfg(all(test, not(target_arch = "wasm32")))]
mod tests {
    use super::*;
    #[test]
    fn failed_rebuild_restores_dropped_tables_and_version() {
        let mut connection = SqliteConnection::establish(":memory:").unwrap();
        connection.batch_execute("CREATE TABLE agent_conversations(conversationId TEXT); CREATE TABLE agent_messages(position INTEGER); CREATE TABLE room_master_table(id INTEGER); INSERT INTO agent_messages VALUES(9); PRAGMA user_version=10; CREATE TEMP VIEW room_master_table AS SELECT 1;").unwrap();
        let names = table_names(&mut connection).unwrap();
        // The temporary view makes a later DROP fail after the business tables were dropped.
        assert!(initialize(&mut connection).is_err());
        assert_eq!(table_names(&mut connection).unwrap(), names);
        assert_eq!(
            diesel::sql_query("SELECT CAST(position AS TEXT) AS name FROM agent_messages")
                .get_result::<Name>(&mut connection)
                .unwrap()
                .name,
            "9"
        );
        assert_eq!(
            diesel::sql_query(
                "SELECT user_version,application_id FROM pragma_user_version,pragma_application_id"
            )
            .get_result::<Metadata>(&mut connection)
            .unwrap()
            .user_version,
            10
        );
        connection
            .batch_execute(
                "INSERT INTO agent_messages VALUES(10); DROP VIEW temp.room_master_table",
            )
            .unwrap();
        initialize(&mut connection).unwrap();
        validate(&mut connection).unwrap();
    }
}
