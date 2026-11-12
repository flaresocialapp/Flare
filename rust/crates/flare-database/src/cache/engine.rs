mod read;
mod write;
use super::{Info, PagingKey, Query};
use crate::Error;
use diesel::{
    prelude::*,
    sql_types::{BigInt, Text},
    sqlite::Sqlite,
};
pub(crate) use read::read;
use std::collections::BTreeSet;
pub(crate) use write::write;

fn select<T: QueryableByName<Sqlite> + 'static>(
    c: &mut SqliteConnection,
    sql: &str,
    args: &[&str],
) -> Result<Vec<T>, Error> {
    let mut query = diesel::sql_query(sql).into_boxed::<Sqlite>();
    for arg in args {
        query = query.bind::<Text, _>(*arg);
    }
    Ok(query.load(c)?)
}
fn execute(c: &mut SqliteConnection, sql: &str, args: &[&str]) -> Result<usize, Error> {
    let mut query = diesel::sql_query(sql).into_boxed::<Sqlite>();
    for arg in args {
        query = query.bind::<Text, _>(*arg);
    }
    Ok(query.execute(c)?)
}
fn invalid(message: &str) -> Error {
    Error::InvalidRequest(message.into())
}
fn json(payload: &str) -> Result<(), Error> {
    serde_json::from_str::<serde::de::IgnoredAny>(payload)
        .map(|_| ())
        .map_err(|_| invalid("invalid cache JSON"))
}
fn nonempty(value: &str) -> Result<(), Error> {
    if value.is_empty() {
        Err(invalid("empty cache key"))
    } else {
        Ok(())
    }
}
#[derive(QueryableByName)]
struct TextValue {
    #[diesel(sql_type=Text)]
    value: String,
}
#[derive(QueryableByName)]
struct Count {
    #[diesel(sql_type=BigInt)]
    value: i64,
}
fn info(c: &mut SqliteConnection) -> Result<Info, Error> {
    let row = select::<TextValue>(c, "SELECT epoch AS value FROM cache_meta WHERE id=1", &[])?
        .pop()
        .ok_or_else(|| Error::Schema("cache epoch missing".into()))?;
    Ok(Info { epoch: row.value })
}
fn metadata(c: &mut SqliteConnection, key: &str) -> Result<Option<PagingKey>, Error> {
    Ok(select(
        c,
        "SELECT * FROM cache_paging_keys WHERE paging_key=?",
        &[key],
    )?
    .pop())
}

#[derive(QueryableByName)]
struct Change {
    #[diesel(sql_type=Text)]
    topic: String,
    #[diesel(sql_type=Text)]
    entry: String,
    #[diesel(sql_type=BigInt)]
    structure: i64,
}
#[derive(Default)]
pub(crate) struct Changes {
    entries: BTreeSet<(String, String)>,
}
impl Changes {
    pub(crate) fn affects(&self, q: &Query) -> bool {
        let has = |topic: &str| self.entries.iter().any(|(t, _)| t == topic);
        let key = |topic: &str, key: &str| self.entries.contains(&(topic.into(), key.into()));
        if has("all") {
            return true;
        }
        match q {
            Query::Info => false,
            Query::TimelinePage { paging_key, .. } | Query::TimelineItems { paging_key, .. } => {
                key("timeline", paging_key) || key("timeline_snapshot", paging_key)
            }
            Query::TimelineIdentities { paging_key, .. }
            | Query::PagingKey(paging_key)
            | Query::SizeThrough { paging_key, .. }
            | Query::NextRow { paging_key, .. } => key("timeline", paging_key),
            Query::AccountTimelines { .. } => {
                has("timeline") || has("timeline_snapshot") || has("status") || has("translation")
            }
            Query::Status(_)
            | Query::Statuses(_)
            | Query::References(_)
            | Query::SearchStatuses { .. } => has("status") || has("translation"),
            Query::Users(_) | Query::UserByHandle { .. } | Query::SearchUsers { .. } => has("user"),
            Query::UserHistory { .. } => has("history") || has("user"),
            Query::Relation { .. } => has("relation"),
            Query::Emoji(_) => has("emoji"),
            Query::EmojiHistory { .. } => has("emoji_history"),
            Query::List { .. }
            | Query::Lists { .. }
            | Query::ListsForUser { .. }
            | Query::ListMemberKeys { .. } => has("list"),
            Query::ListMembers { .. } => has("list") || has("user"),
            Query::Rooms { .. } | Query::Room { .. } => {
                has("room") || has("user") || has("message")
            }
            Query::RoomMessages { room_key, .. } => key("message", room_key) || has("user"),
            Query::LatestRemoteMessage(room) => key("message", room),
            Query::Message(_) => has("message"),
            Query::Translations { .. } => has("translation"),
            Query::Stats => !self.entries.is_empty(),
        }
    }
}
