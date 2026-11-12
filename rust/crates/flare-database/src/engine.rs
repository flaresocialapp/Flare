use crate::{
    AppMutation, AppQuery, AppSnapshot, Commit, Error, agent, cache, migration,
    model::{
        Account, Draft, DraftGroup, DraftMedia, DraftTarget, KeywordFilter, RssSource,
        SearchHistory,
    },
    schema::{
        accounts, draft_groups, draft_media, draft_targets, keyword_filters, rss_sources,
        search_history,
    },
    storage::{Batch, Kind, Query, Receipt, Value, wrong_kind},
};
use diesel::{
    connection::SimpleConnection,
    dsl::{exists, max},
    prelude::*,
};
use std::collections::{BTreeMap, HashMap};
use std::sync::Arc;

#[derive(diesel::QueryableByName)]
struct ForeignKeysEnabled {
    #[diesel(sql_type = diesel::sql_types::Integer)]
    foreign_keys: i32,
}

#[derive(diesel::QueryableByName)]
struct ConnectionSettings {
    #[diesel(sql_type = diesel::sql_types::Text)]
    journal_mode: String,
    #[diesel(sql_type = diesel::sql_types::Integer)]
    synchronous: i32,
    #[diesel(sql_type = diesel::sql_types::Integer)]
    timeout: i32,
}

#[cfg(not(target_arch = "wasm32"))]
type Delivery = Box<dyn Fn(Option<Result<Value, Error>>) -> bool + Send>;
#[cfg(target_arch = "wasm32")]
type Delivery = Box<dyn Fn(Option<Result<Value, Error>>) -> bool>;

struct Observer {
    query: Query,
    deliver: Delivery,
}

pub(crate) struct State {
    pub(crate) connection: SqliteConnection,
    kind: Kind,
    revision: u64,
    observers: BTreeMap<String, Observer>,
}

impl State {
    pub(crate) fn open(
        url: &str,
        kind: Kind,
        backup: impl FnOnce(&mut SqliteConnection, i32) -> Result<(), Error>,
    ) -> Result<Self, Error> {
        let mut connection = SqliteConnection::establish(url)?;
        connection.batch_execute("PRAGMA foreign_keys=ON; PRAGMA busy_timeout=5000;")?;
        if diesel::sql_query("PRAGMA foreign_keys")
            .get_result::<ForeignKeysEnabled>(&mut connection)?
            .foreign_keys
            != 1
        {
            return Err(Error::Schema(
                "SQLite foreign key enforcement unavailable".into(),
            ));
        }
        match kind {
            Kind::App => {
                let legacy = migration::legacy_version(&mut connection)?;
                if let Some(version) = legacy {
                    backup(&mut connection, version)?;
                }
                migration::initialize(&mut connection, legacy)?;
            }
            Kind::Agent => agent::migration::initialize(&mut connection)?,
            Kind::Cache => cache::migration::initialize(&mut connection)?,
        }
        #[cfg(not(target_arch = "wasm32"))]
        connection.batch_execute("PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL;")?;
        #[cfg(target_arch = "wasm32")]
        connection.batch_execute("PRAGMA journal_mode=DELETE; PRAGMA synchronous=FULL;")?;
        let settings = diesel::sql_query("SELECT journal_mode, synchronous, timeout FROM pragma_journal_mode, pragma_synchronous, pragma_busy_timeout")
            .get_result::<ConnectionSettings>(&mut connection)?;
        let journal_mode = if cfg!(target_arch = "wasm32") {
            "delete"
        } else {
            "wal"
        };
        if settings.journal_mode != journal_mode
            || settings.synchronous != 2
            || settings.timeout != 5000
        {
            return Err(Error::Schema(
                "SQLite connection settings were not applied".into(),
            ));
        }
        Ok(Self {
            connection,
            kind,
            revision: 0,
            observers: BTreeMap::new(),
        })
    }

    pub(crate) fn query(&mut self, query: &Query) -> Result<Value, Error> {
        if query.kind() != self.kind {
            return Err(wrong_kind());
        }
        self.connection
            .transaction(|connection| read_value(connection, query))
    }

    pub(crate) fn write(&mut self, mutations: Batch) -> Result<Receipt, Error> {
        if mutations.kind() != self.kind {
            return Err(wrong_kind());
        }
        let mut receipt = match self.kind {
            Kind::App => Receipt::App(Commit {
                revision: self.revision,
                rss_ids: Vec::new(),
            }),
            Kind::Agent => Receipt::Agent(agent::Commit {
                revision: self.revision,
                runs: Vec::new(),
                messages: Vec::new(),
            }),
            Kind::Cache => Receipt::Cache(cache::Commit {
                revision: self.revision,
                epoch: String::new(),
                pages: Vec::new(),
            }),
        };
        if !mutations.is_empty() {
            let mut changes = None;
            self.connection
                .immediate_transaction::<_, Error, _>(|connection| {
                    match (mutations, &mut receipt) {
                        (Batch::App(rows), Receipt::App(receipt)) => {
                            for row in rows {
                                apply(connection, row, &mut receipt.rss_ids)?;
                            }
                        }
                        (Batch::Agent(rows), Receipt::Agent(receipt)) => {
                            for row in rows {
                                agent::engine::apply(connection, row, receipt)?;
                            }
                        }
                        (Batch::Cache { epoch, mutations }, Receipt::Cache(receipt)) => {
                            changes = Some(cache::engine::write(
                                connection, &epoch, mutations, receipt,
                            )?);
                        }
                        _ => return Err(wrong_kind()),
                    }
                    Ok(())
                })?;
            self.revision = self.revision.wrapping_add(1);
            // Publication belongs to the accepted job, not the waiting future.
            // Cache pages can be large; unrelated writes only probe whether the receiver is alive.
            let connection = &mut self.connection;
            self.observers.retain(|_, observer| {
                let affected = match (&observer.query, &changes) {
                    (Query::Cache(q), Some(changes)) => changes.affects(q),
                    _ => true,
                };
                (observer.deliver)(
                    affected.then(|| connection.transaction(|c| read_value(c, &observer.query))),
                )
            });
        }
        match &mut receipt {
            Receipt::App(value) => value.revision = self.revision,
            Receipt::Agent(value) => value.revision = self.revision,
            Receipt::Cache(value) => value.revision = self.revision,
        }
        Ok(receipt)
    }

    pub(crate) fn observe(&mut self, id: String, query: Query, deliver: Delivery) {
        let result = self.query(&query);
        if deliver(Some(result)) {
            self.observers.insert(id, Observer { query, deliver });
        }
    }

    pub(crate) fn unsubscribe(&mut self, id: &str) {
        self.observers.remove(id);
    }
    #[cfg(target_arch = "wasm32")]
    pub(crate) fn unsubscribe_client(&mut self, client: &str) {
        let prefix = format!("{client}:");
        self.observers.retain(|id, _| !id.starts_with(&prefix));
    }
    #[cfg(all(test, not(target_arch = "wasm32")))]
    pub(crate) fn observer_count(&self) -> usize {
        self.observers.len()
    }
}

fn read_value(connection: &mut SqliteConnection, query: &Query) -> Result<Value, Error> {
    match query {
        Query::App(query) => read(connection, query).map(|value| Value::App(Arc::new(value))),
        Query::Agent(query) => {
            agent::engine::read(connection, query).map(|value| Value::Agent(Arc::new(value)))
        }
        Query::Cache(query) => {
            cache::engine::read(connection, query).map(|value| Value::Cache(Arc::new(value)))
        }
    }
}

#[allow(
    clippy::too_many_lines,
    reason = "Exhaustive dispatch of the typed storage queries"
)]
fn read(connection: &mut SqliteConnection, query: &AppQuery) -> Result<AppSnapshot, Error> {
    Ok(match query {
        AppQuery::Accounts => AppSnapshot::Accounts(
            accounts::table
                .order(accounts::account_key)
                .select(Account::as_select())
                .load(connection)?,
        ),
        AppQuery::SortedAccounts => AppSnapshot::Accounts(
            accounts::table
                .order((accounts::sort_id, accounts::account_key))
                .select(Account::as_select())
                .load(connection)?,
        ),
        AppQuery::Account(key) => AppSnapshot::Account(
            accounts::table
                .find(key)
                .select(Account::as_select())
                .first(connection)
                .optional()?,
        ),
        AppQuery::ActiveAccount(platforms) => AppSnapshot::Account(
            accounts::table
                .filter(accounts::platform_type.eq_any(platforms))
                .order((accounts::last_active.desc(), accounts::account_key))
                .select(Account::as_select())
                .first(connection)
                .optional()?,
        ),
        AppQuery::MaxAccountSortId => AppSnapshot::MaxAccountSortId(
            accounts::table
                .select(max(accounts::sort_id))
                .first(connection)?,
        ),
        AppQuery::Draft(id) => AppSnapshot::Draft(load_draft(connection, id)?),
        AppQuery::VisibleDrafts => {
            let groups = draft_groups::table
                .filter(exists(
                    draft_targets::table
                        .filter(draft_targets::group_id.eq(draft_groups::group_id))
                        .filter(draft_targets::status.eq_any(["DRAFT", "FAILED"])),
                ))
                .order((draft_groups::updated_at.desc(), draft_groups::group_id))
                .select(DraftGroup::as_select())
                .load::<DraftGroup>(connection)?;
            AppSnapshot::Drafts(hydrate_drafts(connection, groups)?)
        }
        AppQuery::DraftsByStatus(statuses) => {
            let groups = draft_groups::table
                .filter(exists(
                    draft_targets::table
                        .filter(draft_targets::group_id.eq(draft_groups::group_id))
                        .filter(draft_targets::status.eq_any(statuses)),
                ))
                .order((draft_groups::updated_at.desc(), draft_groups::group_id))
                .select(DraftGroup::as_select())
                .load(connection)?;
            AppSnapshot::Drafts(hydrate_drafts(connection, groups)?)
        }
        AppQuery::DraftGroupsForAccount(key) => AppSnapshot::DraftGroupIds(
            draft_targets::table
                .filter(draft_targets::account_key.eq(key))
                .select(draft_targets::group_id)
                .distinct()
                .order(draft_targets::group_id)
                .load(connection)?,
        ),
        AppQuery::DraftTargetCount { group_id, status } => {
            let mut targets = draft_targets::table
                .filter(draft_targets::group_id.eq(group_id))
                .into_boxed();
            if let Some(status) = status {
                targets = targets.filter(draft_targets::status.eq(status));
            }
            AppSnapshot::DraftTargetCount(targets.count().get_result(connection)?)
        }
        AppQuery::KeywordFilters => AppSnapshot::KeywordFilters(
            keyword_filters::table
                .order(keyword_filters::keyword)
                .select(KeywordFilter::as_select())
                .load(connection)?,
        ),
        AppQuery::KeywordFilter(keyword) => AppSnapshot::KeywordFilter(
            keyword_filters::table
                .find(keyword)
                .select(KeywordFilter::as_select())
                .first(connection)
                .optional()?,
        ),
        AppQuery::ActiveKeywordFilters { now, flags } => {
            let mut filters = keyword_filters::table
                .filter(
                    keyword_filters::expired_at
                        .eq(0)
                        .or(keyword_filters::expired_at.gt(now)),
                )
                .into_boxed();
            if let Some((timeline, notification, search)) = flags {
                filters = filters.filter(
                    keyword_filters::for_timeline
                        .eq(timeline)
                        .or(keyword_filters::for_notification.eq(notification))
                        .or(keyword_filters::for_search.eq(search)),
                );
            }
            AppSnapshot::KeywordFilters(
                filters
                    .order(keyword_filters::keyword)
                    .select(KeywordFilter::as_select())
                    .load(connection)?,
            )
        }
        AppQuery::SearchHistory => AppSnapshot::SearchHistory(
            search_history::table
                .order((search_history::created_at.desc(), search_history::search))
                .select(SearchHistory::as_select())
                .load(connection)?,
        ),
        AppQuery::RssSources => AppSnapshot::RssSources(
            rss_sources::table
                .order(rss_sources::id)
                .select(RssSource::as_select())
                .load(connection)?,
        ),
        AppQuery::RssSource(id) => AppSnapshot::RssSource(
            rss_sources::table
                .find(id)
                .select(RssSource::as_select())
                .first(connection)
                .optional()?,
        ),
        AppQuery::RssByUrl {
            url,
            subscription_type,
        } => {
            let mut query = rss_sources::table
                .filter(rss_sources::url.eq(url))
                .into_boxed();
            if let Some(kind) = subscription_type {
                query = query.filter(rss_sources::subscription_type.eq(kind));
            }
            AppSnapshot::RssSources(
                query
                    .order(rss_sources::id)
                    .select(RssSource::as_select())
                    .load(connection)?,
            )
        }
    })
}

fn load_draft(connection: &mut SqliteConnection, id: &str) -> Result<Option<Draft>, Error> {
    let group = draft_groups::table
        .find(id)
        .select(DraftGroup::as_select())
        .first::<DraftGroup>(connection)
        .optional()?;
    match group {
        Some(group) => Ok(hydrate_drafts(connection, vec![group])?.pop()),
        None => Ok(None),
    }
}

fn hydrate_drafts(
    connection: &mut SqliteConnection,
    groups: Vec<DraftGroup>,
) -> Result<Vec<Draft>, Error> {
    let mut targets: HashMap<String, Vec<DraftTarget>> = HashMap::new();
    let mut media: HashMap<String, Vec<DraftMedia>> = HashMap::new();
    for batch in groups.chunks(500) {
        let ids: Vec<_> = batch.iter().map(|group| &group.group_id).collect();
        let rows = draft_targets::table
            .filter(draft_targets::group_id.eq_any(&ids))
            .order((draft_targets::created_at, draft_targets::target_id))
            .select(DraftTarget::as_select())
            .load::<DraftTarget>(connection)?;
        for row in rows {
            targets.entry(row.group_id.clone()).or_default().push(row);
        }
        let rows = draft_media::table
            .filter(draft_media::group_id.eq_any(&ids))
            .order((draft_media::sort_order, draft_media::media_id))
            .select(DraftMedia::as_select())
            .load::<DraftMedia>(connection)?;
        for row in rows {
            media.entry(row.group_id.clone()).or_default().push(row);
        }
    }
    Ok(groups
        .into_iter()
        .map(|group| Draft {
            targets: targets.remove(&group.group_id).unwrap_or_default(),
            media: media.remove(&group.group_id).unwrap_or_default(),
            group,
        })
        .collect())
}

fn upsert_group(connection: &mut SqliteConnection, group: &DraftGroup) -> Result<(), Error> {
    diesel::insert_into(draft_groups::table)
        .values(group)
        .on_conflict(draft_groups::group_id)
        .do_update()
        .set(group)
        .execute(connection)?;
    Ok(())
}

#[allow(
    clippy::too_many_lines,
    reason = "Exhaustive dispatch; multi-statement operations use helpers"
)]
fn apply(
    connection: &mut SqliteConnection,
    mutation: AppMutation,
    rss_ids: &mut Vec<i64>,
) -> Result<(), Error> {
    match mutation {
        AppMutation::UpsertAccount(row) => {
            diesel::insert_into(accounts::table)
                .values(&row)
                .on_conflict(accounts::account_key)
                .do_update()
                .set(&row)
                .execute(connection)?;
        }
        AppMutation::DeleteAccount(key) => {
            diesel::delete(accounts::table.find(key)).execute(connection)?;
        }
        AppMutation::SetAccountCredential {
            account_key,
            credential_json,
        } => {
            diesel::update(accounts::table.find(account_key))
                .set(accounts::credential_json.eq(credential_json))
                .execute(connection)?;
        }
        AppMutation::SetAccountLastActive {
            account_key,
            last_active,
        } => {
            diesel::update(accounts::table.find(account_key))
                .set(accounts::last_active.eq(last_active))
                .execute(connection)?;
        }
        AppMutation::SetAccountSortId {
            account_key,
            sort_id,
        } => {
            diesel::update(accounts::table.find(account_key))
                .set(accounts::sort_id.eq(sort_id))
                .execute(connection)?;
        }
        AppMutation::UpsertDraftGroup(group) => upsert_group(connection, &group)?,
        AppMutation::UpsertDraftTarget(row) => {
            diesel::replace_into(draft_targets::table)
                .values(row)
                .execute(connection)?;
        }
        AppMutation::UpdateDraftTargetStatus(update) => {
            diesel::update(draft_targets::table.find(&update.target_id))
                .set(&update)
                .execute(connection)?;
        }
        AppMutation::UpsertDraftMedia(row) => {
            diesel::replace_into(draft_media::table)
                .values(row)
                .execute(connection)?;
        }
        AppMutation::SaveDraft(draft) => save_draft(connection, draft)?,
        AppMutation::DeleteDraft(id) => {
            diesel::delete(draft_groups::table.find(id)).execute(connection)?;
        }
        AppMutation::DeleteDraftTarget(id) => {
            diesel::delete(draft_targets::table.find(id)).execute(connection)?;
        }
        AppMutation::DeleteDraftTargets(id) => {
            diesel::delete(draft_targets::table.filter(draft_targets::group_id.eq(id)))
                .execute(connection)?;
        }
        AppMutation::DeleteDraftMedia(id) => {
            diesel::delete(draft_media::table.find(id)).execute(connection)?;
        }
        AppMutation::DeleteDraftMediaForGroup(id) => {
            diesel::delete(draft_media::table.filter(draft_media::group_id.eq(id)))
                .execute(connection)?;
        }
        AppMutation::TouchDraft {
            group_id,
            updated_at,
        } => {
            diesel::update(draft_groups::table.find(group_id))
                .set(draft_groups::updated_at.eq(updated_at))
                .execute(connection)?;
        }
        AppMutation::RecoverDraftTargets {
            from_status,
            to_status,
            expired_before,
            error_message,
            updated_at,
        } => {
            recover_targets(
                connection,
                &from_status,
                &to_status,
                expired_before,
                error_message.as_deref(),
                updated_at,
            )?;
        }
        AppMutation::UpsertKeywordFilter(row) => {
            diesel::insert_into(keyword_filters::table)
                .values(&row)
                .on_conflict(keyword_filters::keyword)
                .do_update()
                .set(&row)
                .execute(connection)?;
        }
        AppMutation::UpdateKeywordFilter(row) => {
            diesel::update(keyword_filters::table.find(&row.keyword))
                .set(&row)
                .execute(connection)?;
        }
        AppMutation::DeleteKeywordFilter(keyword) => {
            diesel::delete(keyword_filters::table.find(keyword)).execute(connection)?;
        }
        AppMutation::ClearKeywordFilters => {
            diesel::delete(keyword_filters::table).execute(connection)?;
        }
        AppMutation::UpsertSearchHistory(row) => {
            diesel::insert_into(search_history::table)
                .values(&row)
                .on_conflict(search_history::search)
                .do_update()
                .set(&row)
                .execute(connection)?;
        }
        AppMutation::DeleteSearchHistory(search) => {
            diesel::delete(search_history::table.find(search)).execute(connection)?;
        }
        AppMutation::ClearSearchHistory => {
            diesel::delete(search_history::table).execute(connection)?;
        }
        AppMutation::UpsertRssSource(row) => rss_ids.push(upsert_rss(connection, &row)?),
        AppMutation::DeleteRssSource(id) => {
            diesel::delete(rss_sources::table.find(id)).execute(connection)?;
        }
    }
    Ok(())
}

fn save_draft(connection: &mut SqliteConnection, draft: Draft) -> Result<(), Error> {
    if draft
        .targets
        .iter()
        .any(|row| row.group_id != draft.group.group_id)
        || draft
            .media
            .iter()
            .any(|row| row.group_id != draft.group.group_id)
    {
        return Err(Error::InvalidRequest(
            "draft child belongs to another group".into(),
        ));
    }
    upsert_group(connection, &draft.group)?;
    diesel::delete(draft_targets::table.filter(draft_targets::group_id.eq(&draft.group.group_id)))
        .execute(connection)?;
    diesel::delete(draft_media::table.filter(draft_media::group_id.eq(&draft.group.group_id)))
        .execute(connection)?;
    for row in draft.targets {
        diesel::insert_into(draft_targets::table)
            .values(row)
            .execute(connection)?;
    }
    for row in draft.media {
        diesel::insert_into(draft_media::table)
            .values(row)
            .execute(connection)?;
    }
    Ok(())
}

fn recover_targets(
    connection: &mut SqliteConnection,
    from: &str,
    to: &str,
    cutoff: Option<i64>,
    message: Option<&str>,
    updated_at: i64,
) -> Result<(), Error> {
    use diesel::sql_types::{BigInt, Nullable, Text};
    // Touch the matching groups before changing the predicate's target status.
    diesel::sql_query("UPDATE DbDraftGroup SET updated_at=? WHERE group_id IN (SELECT DISTINCT group_id FROM DbDraftTarget WHERE status=? AND (? IS NULL OR last_attempt_at IS NULL OR last_attempt_at<?))")
        .bind::<BigInt,_>(updated_at).bind::<Text,_>(from).bind::<Nullable<BigInt>,_>(cutoff)
        .bind::<Nullable<BigInt>,_>(cutoff).execute(connection)?;
    diesel::sql_query("UPDATE DbDraftTarget SET status=?,error_message=?,updated_at=? WHERE status=? AND (? IS NULL OR last_attempt_at IS NULL OR last_attempt_at<?)")
        .bind::<Text,_>(to).bind::<Nullable<Text>,_>(message).bind::<BigInt,_>(updated_at).bind::<Text,_>(from)
        .bind::<Nullable<BigInt>,_>(cutoff).bind::<Nullable<BigInt>,_>(cutoff).execute(connection)?;
    Ok(())
}

fn upsert_rss(connection: &mut SqliteConnection, row: &RssSource) -> Result<i64, Error> {
    Ok(if row.id == 0 {
        diesel::insert_into(rss_sources::table)
            .values((
                rss_sources::url.eq(&row.url),
                rss_sources::title.eq(&row.title),
                rss_sources::icon.eq(&row.icon),
                rss_sources::display_mode.eq(&row.display_mode),
                rss_sources::last_update.eq(row.last_update),
                rss_sources::subscription_type.eq(&row.subscription_type),
            ))
            .returning(rss_sources::id)
            .get_result(connection)?
    } else {
        diesel::insert_into(rss_sources::table)
            .values(row)
            .on_conflict(rss_sources::id)
            .do_update()
            .set(row)
            .returning(rss_sources::id)
            .get_result(connection)?
    })
}

#[cfg(all(test, not(target_arch = "wasm32")))]
mod tests {
    use super::*;
    use std::sync::{
        Arc,
        atomic::{AtomicUsize, Ordering},
    };

    #[test]
    fn cache_hot_queries_use_indexes_instead_of_scanning_translation_or_room_tables() {
        use diesel::connection::InstrumentationEvent;
        use std::sync::Mutex;
        #[derive(diesel::QueryableByName)]
        struct Plan {
            #[diesel(sql_type=diesel::sql_types::Text)]
            detail: String,
        }
        let directory = tempfile::tempdir().unwrap();
        let mut state = State::open(
            directory.path().join("cache.db").to_str().unwrap(),
            Kind::Cache,
            |_, _| panic!("fresh database"),
        )
        .unwrap();
        let Value::Cache(info) = state.query(&Query::Cache(cache::Query::Info)).unwrap() else {
            panic!()
        };
        let cache::Snapshot::Info(info) = info.as_ref() else {
            panic!()
        };
        let epoch = info.epoch.clone();
        let captured = Arc::new(Mutex::new(Vec::new()));
        let log = Arc::clone(&captured);
        state
            .connection
            .set_instrumentation(move |event: InstrumentationEvent<'_>| {
                if let InstrumentationEvent::StartQuery { query, .. } = event {
                    log.lock().unwrap().push(query.to_string());
                }
            });
        state
            .query(&Query::Cache(cache::Query::Statuses(vec!["probe".into()])))
            .unwrap();
        state
            .query(&Query::Cache(cache::Query::Translations {
                entity_type: "Profile".into(),
                entity_keys: vec!["probe".into()],
                language: None,
            }))
            .unwrap();
        state
            .write(Batch::Cache {
                epoch,
                mutations: vec![cache::Mutation::Put(cache::Record::RoomTimeline(
                    cache::RoomTimeline {
                        account: "a".into(),
                        room_key: "room".into(),
                        payload: "{}".into(),
                        ..Default::default()
                    },
                ))],
            })
            .unwrap();
        state
            .query(&Query::Cache(cache::Query::AccountTimelines {
                account: "a".into(),
                after: Some(cache::TimelineCursor {
                    status_id: "probe".into(),
                    paging_key: "home".into(),
                    timeline_id: "row".into(),
                }),
                limit: 100,
            }))
            .unwrap();
        state
            .connection
            .set_instrumentation(|_: InstrumentationEvent<'_>| {});
        let queries = captured.lock().unwrap();
        let mut checked = 0;
        for sql in queries.iter().filter(|sql| {
            sql.starts_with("SELECT * FROM cache_translations")
                || sql.starts_with("UPDATE cache_room_timelines")
                || sql.starts_with("SELECT t.* FROM cache_statuses")
        }) {
            let statement = sql
                .split(" -- binds:")
                .next()
                .unwrap()
                .replace('?', "'probe'");
            let plan = diesel::sql_query(format!("EXPLAIN QUERY PLAN {statement}"))
                .load::<Plan>(&mut state.connection)
                .unwrap();
            assert!(
                !plan.iter().any(|p| p.detail.starts_with("SCAN ")),
                "{statement}: {}",
                plan.iter()
                    .map(|p| p.detail.as_str())
                    .collect::<Vec<_>>()
                    .join("; ")
            );
            checked += 1;
        }
        assert_eq!(checked, 4);
    }

    #[test]
    fn commit_failure_rolls_back_without_publishing_and_connection_recovers() {
        let directory = tempfile::tempdir().unwrap();
        let path = directory.path().join("app.db");
        let mut state = State::open(path.to_str().unwrap(), Kind::App, |_, _| {
            panic!("fresh database")
        })
        .unwrap();
        let updates = Arc::new(AtomicUsize::new(0));
        let count = Arc::clone(&updates);
        state.observe(
            "observe".into(),
            Query::App(AppQuery::Accounts),
            Box::new(move |_| {
                count.fetch_add(1, Ordering::SeqCst);
                true
            }),
        );
        state
            .connection
            .batch_execute("PRAGMA defer_foreign_keys=ON")
            .unwrap();
        let invalid = DraftTarget {
            group_id: "missing".into(),
            account_key: "a".into(),
            status: "FAILED".into(),
            error_message: None,
            attempt_count: 0,
            last_attempt_at: None,
            created_at: 0,
            updated_at: 0,
            target_id: "t".into(),
        };
        let account = Account {
            account_key: "a".into(),
            credential_json: "{}".into(),
            platform_type: "x".into(),
            last_active: 0,
            sort_id: 0,
        };
        assert!(
            state
                .write(Batch::App(vec![
                    AppMutation::UpsertAccount(account.clone()),
                    AppMutation::UpsertDraftTarget(invalid)
                ]))
                .is_err()
        );
        assert_eq!(updates.load(Ordering::SeqCst), 1);
        assert_eq!(
            state
                .query(&Query::App(AppQuery::Accounts))
                .unwrap()
                .app()
                .unwrap()
                .as_ref(),
            &AppSnapshot::Accounts(vec![])
        );
        assert_eq!(
            state
                .write(Batch::App(vec![AppMutation::UpsertAccount(account)]))
                .unwrap()
                .app()
                .unwrap()
                .revision,
            1
        );
        assert_eq!(updates.load(Ordering::SeqCst), 2);
    }
}
