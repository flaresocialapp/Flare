use crate::Error;
use diesel::{
    Connection, QueryableByName, RunQueryDsl, SqliteConnection,
    connection::SimpleConnection,
    sql_query,
    sql_types::{Integer, Nullable, Text},
};
use serde::Deserialize;
use std::collections::BTreeSet;

pub(crate) const VERSION: i32 = 13;
// Distinguish this baseline from unsupported development databases also numbered 13.
const APPLICATION_ID: i32 = 0x464c_4152;

// These are immutable historical inputs, shared with the existing Room fixtures.
const ROOM: [&str; 10] = [
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

#[derive(Deserialize)]
struct Export {
    database: RoomSchema,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct RoomSchema {
    identity_hash: String,
    entities: Vec<Entity>,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Entity {
    table_name: String,
    create_sql: String,
    #[serde(default)]
    indices: Vec<IndexSql>,
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct IndexSql {
    create_sql: String,
}

fn room(version: i32) -> Result<RoomSchema, Error> {
    let index = usize::try_from(version - 3).map_err(|_| Error::UnsupportedVersion(version))?;
    let source = ROOM.get(index).ok_or(Error::UnsupportedVersion(version))?;
    serde_json::from_str::<Export>(source)
        .map(|v| v.database)
        .map_err(|_| Error::Schema("invalid embedded Room schema".into()))
}

#[derive(QueryableByName)]
struct UserVersion {
    #[diesel(sql_type = Integer)]
    user_version: i32,
}
#[derive(QueryableByName)]
struct ApplicationId {
    #[diesel(sql_type = Integer)]
    application_id: i32,
}
#[derive(QueryableByName)]
struct Identity {
    #[diesel(sql_type = Text)]
    identity_hash: String,
}
#[derive(QueryableByName)]
struct Name {
    #[diesel(sql_type = Text)]
    name: String,
}

#[derive(Debug, PartialEq, Eq, PartialOrd, Ord, QueryableByName)]
struct Column {
    #[diesel(sql_type = Text)]
    name: String,
    #[diesel(sql_type = Text, column_name = "type")]
    sql_type: String,
    #[diesel(sql_type = Integer, column_name = "notnull")]
    not_null: i32,
    #[diesel(sql_type = Nullable<Text>, column_name = "dflt_value")]
    default: Option<String>,
    #[diesel(sql_type = Integer)]
    pk: i32,
}
#[derive(QueryableByName)]
struct IndexRow {
    #[diesel(sql_type = Text)]
    name: String,
    #[diesel(sql_type = Integer, column_name = "unique")]
    is_unique: i32,
    #[diesel(sql_type = Text)]
    origin: String,
    #[diesel(sql_type = Integer)]
    partial: i32,
}
#[derive(Debug, PartialEq, Eq, PartialOrd, Ord, QueryableByName)]
struct IndexColumn {
    #[diesel(sql_type = Nullable<Text>)]
    name: Option<String>,
    #[diesel(sql_type = Integer, column_name = "desc")]
    descending: i32,
    #[diesel(sql_type = Text, column_name = "coll")]
    collation: String,
    #[diesel(sql_type = Integer)]
    key: i32,
}
#[derive(Debug, PartialEq, Eq, PartialOrd, Ord)]
struct Index {
    name: String,
    unique: i32,
    partial: i32,
    columns: Vec<IndexColumn>,
}
#[derive(Debug, PartialEq, Eq, PartialOrd, Ord, QueryableByName)]
struct ForeignKey {
    #[diesel(sql_type = Integer)]
    seq: i32,
    #[diesel(sql_type = Text, column_name = "table")]
    table: String,
    #[diesel(sql_type = Text, column_name = "from")]
    from: String,
    #[diesel(sql_type = Nullable<Text>, column_name = "to")]
    to: Option<String>,
    #[diesel(sql_type = Text)]
    on_update: String,
    #[diesel(sql_type = Text)]
    on_delete: String,
    #[diesel(sql_type = Text, column_name = "match")]
    matching: String,
}
#[derive(Debug, PartialEq, Eq)]
pub(crate) struct Table {
    columns: BTreeSet<Column>,
    indices: BTreeSet<Index>,
    foreign_keys: BTreeSet<ForeignKey>,
}

pub(crate) fn quoted(identifier: &str) -> String {
    format!("\"{}\"", identifier.replace('"', "\"\""))
}

pub(crate) fn table(connection: &mut SqliteConnection, name: &str) -> Result<Table, Error> {
    let name = quoted(name);
    let columns = sql_query(format!("PRAGMA table_info({name})")).load::<Column>(connection)?;
    let rows = sql_query(format!("PRAGMA index_list({name})")).load::<IndexRow>(connection)?;
    let mut indices = BTreeSet::new();
    for row in rows {
        let columns = sql_query(format!("PRAGMA index_xinfo({})", quoted(&row.name)))
            .load::<IndexColumn>(connection)?;
        indices.insert(Index {
            name: if row.origin == "pk" {
                "<primary>".into()
            } else {
                row.name
            },
            unique: row.is_unique,
            partial: row.partial,
            columns,
        });
    }
    Ok(Table {
        columns: columns.into_iter().collect(),
        indices,
        foreign_keys: sql_query(format!("PRAGMA foreign_key_list({name})"))
            .load::<ForeignKey>(connection)?
            .into_iter()
            .collect(),
    })
}

pub(crate) fn table_names(connection: &mut SqliteConnection) -> Result<BTreeSet<String>, Error> {
    Ok(
        sql_query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")
            .load::<Name>(connection)?
            .into_iter()
            .map(|r| r.name)
            .collect(),
    )
}

fn create_entity(connection: &mut SqliteConnection, entity: &Entity) -> Result<(), Error> {
    connection.batch_execute(
        &entity
            .create_sql
            .replace("${TABLE_NAME}", &entity.table_name),
    )?;
    for index in &entity.indices {
        connection.batch_execute(
            &index
                .create_sql
                .replace("${TABLE_NAME}", &entity.table_name),
        )?;
    }
    Ok(())
}

pub(crate) fn create_room_tables(
    connection: &mut SqliteConnection,
    version: i32,
) -> Result<(), Error> {
    for entity in room(version)?.entities {
        create_entity(connection, &entity)?;
    }
    Ok(())
}

fn validate(connection: &mut SqliteConnection, version: i32, migrated: bool) -> Result<(), Error> {
    let expected_schema = room(version)?;
    let mut reference = SqliteConnection::establish(":memory:")?;
    create_room_tables(&mut reference, version)?;
    let mut names = table_names(connection)?;
    names.remove("room_master_table");
    names.remove("android_metadata");
    for entity in expected_schema.entities {
        if !names.remove(&entity.table_name) {
            return Err(Error::Schema(format!(
                "missing table {}",
                entity.table_name
            )));
        }
        let mut actual = table(connection, &entity.table_name)?;
        if migrated && entity.table_name == "DbRssSources" {
            // Preserve the obsolete source value; the active display mode is migrated below.
            actual.columns.remove(&Column {
                name: "openInBrowser".into(),
                sql_type: "INTEGER".into(),
                not_null: 1,
                default: Some("0".into()),
                pk: 0,
            });
        }
        if actual != table(&mut reference, &entity.table_name)? {
            return Err(Error::Schema(format!("layout of {}", entity.table_name)));
        }
    }
    if migrated && names.remove("DbApplication") {
        let retired = room(3)?
            .entities
            .into_iter()
            .find(|e| e.table_name == "DbApplication")
            .ok_or_else(|| Error::Schema("retired app schema missing".into()))?;
        create_entity(&mut reference, &retired)?;
        if table(connection, "DbApplication")? != table(&mut reference, "DbApplication")? {
            return Err(Error::Schema("layout of retired DbApplication".into()));
        }
    }
    if !names.is_empty() {
        return Err(Error::Schema("unexpected tables".into()));
    }
    let unexpected_triggers =
        sql_query("SELECT name FROM sqlite_master WHERE type IN ('trigger','view')")
            .load::<Name>(connection)?;
    if !unexpected_triggers.is_empty() {
        return Err(Error::Schema("unexpected app triggers or views".into()));
    }
    if !sql_query("PRAGMA foreign_key_check")
        .load::<ForeignKeyViolation>(connection)?
        .is_empty()
    {
        return Err(Error::Schema("foreign key violations".into()));
    }
    Ok(())
}

#[derive(QueryableByName)]
struct ForeignKeyViolation {
    #[diesel(sql_type = Text, column_name = "table")]
    _table: String,
}

/// Read-only validation runs before backup, journal changes, or DDL.
pub(crate) fn legacy_version(connection: &mut SqliteConnection) -> Result<Option<i32>, Error> {
    let version = sql_query("PRAGMA user_version")
        .get_result::<UserVersion>(connection)?
        .user_version;
    let application = sql_query("PRAGMA application_id")
        .get_result::<ApplicationId>(connection)?
        .application_id;
    if version == VERSION && application == APPLICATION_ID {
        validate(connection, 12, true)?;
        return Ok(None);
    }
    if version == 0 && application == 0 && table_names(connection)?.is_empty() {
        return Ok(None);
    }
    if !(3..=12).contains(&version) || application != 0 {
        return Err(Error::UnsupportedVersion(version));
    }
    if !table_names(connection)?.contains("room_master_table") {
        return Err(Error::Schema(
            "missing Room identity; SQLDelight is unsupported".into(),
        ));
    }
    let identity = sql_query("SELECT identity_hash FROM room_master_table WHERE id=42")
        .get_result::<Identity>(connection)?;
    if identity.identity_hash != room(version)?.identity_hash {
        return Err(Error::Schema("unrecognized Room identity".into()));
    }
    validate(connection, version, false)?;
    Ok(Some(version))
}

pub(crate) fn initialize(
    connection: &mut SqliteConnection,
    legacy: Option<i32>,
) -> Result<(), Error> {
    let version = sql_query("PRAGMA user_version")
        .get_result::<UserVersion>(connection)?
        .user_version;
    if version == VERSION {
        return Ok(());
    }
    connection.immediate_transaction::<_, Error, _>(|connection| {
        if let Some(from) = legacy {
            for version in from..12 {
                upgrade(connection, version)?;
            }
        } else {
            create_room_tables(connection, 12)?;
        }
        validate(connection, 12, true)?;
        connection.batch_execute(&format!(
            "PRAGMA user_version={VERSION}; PRAGMA application_id={APPLICATION_ID};"
        ))?;
        Ok(())
    })
}

fn upgrade(connection: &mut SqliteConnection, from: i32) -> Result<(), Error> {
    match from {
        3 | 5 => {
            let current: BTreeSet<_> = room(from)?.entities.into_iter().map(|e| e.table_name).collect();
            for entity in room(from + 1)?.entities {
                if !current.contains(&entity.table_name) { create_entity(connection, &entity)?; }
            }
        }
        4 => connection.batch_execute("ALTER TABLE DbRssSources ADD COLUMN icon TEXT; ALTER TABLE DbRssSources ADD COLUMN openInBrowser INTEGER NOT NULL DEFAULT 0;")?,
        6 => connection.batch_execute("ALTER TABLE DbAccount ADD COLUMN sort_id INTEGER NOT NULL DEFAULT 0;")?,
        7 => connection.batch_execute("ALTER TABLE DbRssSources ADD COLUMN type TEXT NOT NULL DEFAULT 'RSS';")?,
        8 => connection.batch_execute("ALTER TABLE DbRssSources ADD COLUMN displayMode TEXT NOT NULL DEFAULT 'FULL_CONTENT'; UPDATE DbRssSources SET displayMode='OPEN_IN_BROWSER' WHERE openInBrowser=1;")?,
        9 => connection.batch_execute("ALTER TABLE DbKeywordFilter ADD COLUMN is_regex INTEGER NOT NULL DEFAULT 0;")?,
        // Keep retired DbApplication rows, unlike the former destructive 10→11 step.
        10 | 11 => {},
        _ => return Err(Error::UnsupportedVersion(from)),
    }
    Ok(())
}

#[cfg(all(test, not(target_arch = "wasm32")))]
mod tests {
    use super::*;

    #[test]
    fn ddl_failure_rolls_back_earlier_steps_and_version_metadata() {
        let mut connection = SqliteConnection::establish(":memory:").unwrap();
        create_room_tables(&mut connection, 3).unwrap();
        connection
            .batch_execute("PRAGMA user_version=3; CREATE VIEW DbDraftTarget AS SELECT 1;")
            .unwrap();
        let before = table_names(&mut connection).unwrap();
        // Inject a later CREATE failure after RSS has already been upgraded.
        assert!(initialize(&mut connection, Some(3)).is_err());
        assert_eq!(table_names(&mut connection).unwrap(), before);
        assert_eq!(
            sql_query("PRAGMA user_version")
                .get_result::<UserVersion>(&mut connection)
                .unwrap()
                .user_version,
            3
        );
        assert_eq!(
            sql_query("PRAGMA application_id")
                .get_result::<ApplicationId>(&mut connection)
                .unwrap()
                .application_id,
            0
        );
    }
}
