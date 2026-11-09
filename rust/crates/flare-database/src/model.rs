//! Owned storage values. Historical keys, JSON, and enum strings retain their exact encoding.

use crate::schema;
use diesel::{AsChangeset, Insertable, Queryable, Selectable};
use serde::{Deserialize, Serialize};

#[derive(
    Clone, PartialEq, Eq, Serialize, Deserialize, Queryable, Selectable, Insertable, AsChangeset,
)]
#[diesel(table_name = schema::accounts, primary_key(account_key))]
pub struct Account {
    pub account_key: String,
    pub credential_json: String,
    pub platform_type: String,
    pub last_active: i64,
    pub sort_id: i64,
}

impl std::fmt::Debug for Account {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Account")
            .field("account_key", &self.account_key)
            .field("platform_type", &self.platform_type)
            .field("last_active", &self.last_active)
            .field("sort_id", &self.sort_id)
            .finish_non_exhaustive()
    }
}

#[derive(
    Clone, PartialEq, Eq, Serialize, Deserialize, Queryable, Selectable, Insertable, AsChangeset,
)]
#[diesel(table_name = schema::draft_groups, primary_key(group_id))]
pub struct DraftGroup {
    pub group_id: String,
    pub content: String,
    pub created_at: i64,
    pub updated_at: i64,
}

impl std::fmt::Debug for DraftGroup {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("DraftGroup")
            .field("group_id", &self.group_id)
            .field("created_at", &self.created_at)
            .field("updated_at", &self.updated_at)
            .finish_non_exhaustive()
    }
}

#[derive(
    Clone,
    Debug,
    PartialEq,
    Eq,
    Serialize,
    Deserialize,
    Queryable,
    Selectable,
    Insertable,
    AsChangeset,
)]
#[diesel(table_name = schema::draft_targets, primary_key(target_id), treat_none_as_null = true)]
pub struct DraftTarget {
    pub group_id: String,
    pub account_key: String,
    pub status: String,
    pub error_message: Option<String>,
    pub attempt_count: i32,
    pub last_attempt_at: Option<i64>,
    pub created_at: i64,
    pub updated_at: i64,
    pub target_id: String,
}

#[derive(Clone, Serialize, Deserialize, AsChangeset)]
#[diesel(table_name = schema::draft_targets, primary_key(target_id), treat_none_as_null = true)]
pub struct TargetStatusUpdate {
    pub target_id: String,
    pub status: String,
    pub error_message: Option<String>,
    pub attempt_count: i32,
    pub last_attempt_at: Option<i64>,
    pub updated_at: i64,
}

#[derive(
    Clone,
    Debug,
    PartialEq,
    Eq,
    Serialize,
    Deserialize,
    Queryable,
    Selectable,
    Insertable,
    AsChangeset,
)]
#[diesel(table_name = schema::draft_media, primary_key(media_id), treat_none_as_null = true)]
pub struct DraftMedia {
    pub group_id: String,
    pub cache_path: String,
    pub file_name: Option<String>,
    pub media_type: String,
    pub alt_text: Option<String>,
    pub sort_order: i32,
    pub created_at: i64,
    pub media_id: String,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Draft {
    pub group: DraftGroup,
    pub targets: Vec<DraftTarget>,
    pub media: Vec<DraftMedia>,
}

#[derive(
    Clone,
    Debug,
    PartialEq,
    Eq,
    Serialize,
    Deserialize,
    Queryable,
    Selectable,
    Insertable,
    AsChangeset,
)]
#[diesel(table_name = schema::keyword_filters, primary_key(keyword))]
pub struct KeywordFilter {
    pub keyword: String,
    pub for_timeline: i64,
    pub for_notification: i64,
    pub for_search: i64,
    pub expired_at: i64,
    pub is_regex: i64,
}

#[derive(
    Clone,
    Debug,
    PartialEq,
    Eq,
    Serialize,
    Deserialize,
    Queryable,
    Selectable,
    Insertable,
    AsChangeset,
)]
#[diesel(table_name = schema::search_history, primary_key(search))]
pub struct SearchHistory {
    pub search: String,
    pub created_at: i64,
}

#[derive(
    Clone,
    Debug,
    PartialEq,
    Eq,
    Serialize,
    Deserialize,
    Queryable,
    Selectable,
    Insertable,
    AsChangeset,
)]
#[diesel(table_name = schema::rss_sources, treat_none_as_null = true)]
pub struct RssSource {
    /// Zero allocates an ID, matching Room's auto-generated primary key convention.
    pub id: i64,
    pub url: String,
    pub title: Option<String>,
    pub icon: Option<String>,
    pub display_mode: String,
    pub last_update: i64,
    pub subscription_type: String,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum AppQuery {
    Accounts,
    SortedAccounts,
    Account(String),
    ActiveAccount(Vec<String>),
    MaxAccountSortId,
    Draft(String),
    VisibleDrafts,
    DraftsByStatus(Vec<String>),
    DraftGroupsForAccount(String),
    DraftTargetCount {
        group_id: String,
        status: Option<String>,
    },
    KeywordFilters,
    KeywordFilter(String),
    ActiveKeywordFilters {
        now: i64,
        flags: Option<(i64, i64, i64)>,
    },
    SearchHistory,
    RssSources,
    RssSource(i64),
    RssByUrl {
        url: String,
        subscription_type: Option<String>,
    },
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum AppSnapshot {
    Accounts(Vec<Account>),
    Account(Option<Account>),
    MaxAccountSortId(Option<i64>),
    Draft(Option<Draft>),
    Drafts(Vec<Draft>),
    DraftGroupIds(Vec<String>),
    DraftTargetCount(i64),
    KeywordFilters(Vec<KeywordFilter>),
    KeywordFilter(Option<KeywordFilter>),
    SearchHistory(Vec<SearchHistory>),
    RssSources(Vec<RssSource>),
    RssSource(Option<RssSource>),
}

#[derive(Clone, Serialize, Deserialize)]
pub enum AppMutation {
    UpsertAccount(Account),
    DeleteAccount(String),
    SetAccountCredential {
        account_key: String,
        credential_json: String,
    },
    SetAccountLastActive {
        account_key: String,
        last_active: i64,
    },
    SetAccountSortId {
        account_key: String,
        sort_id: i64,
    },
    UpsertDraftGroup(DraftGroup),
    UpsertDraftTarget(DraftTarget),
    UpdateDraftTargetStatus(TargetStatusUpdate),
    UpsertDraftMedia(DraftMedia),
    SaveDraft(Draft),
    DeleteDraft(String),
    DeleteDraftTarget(String),
    DeleteDraftTargets(String),
    DeleteDraftMedia(String),
    DeleteDraftMediaForGroup(String),
    TouchDraft {
        group_id: String,
        updated_at: i64,
    },
    RecoverDraftTargets {
        from_status: String,
        to_status: String,
        expired_before: Option<i64>,
        error_message: Option<String>,
        updated_at: i64,
    },
    UpsertKeywordFilter(KeywordFilter),
    UpdateKeywordFilter(KeywordFilter),
    DeleteKeywordFilter(String),
    ClearKeywordFilters,
    UpsertSearchHistory(SearchHistory),
    DeleteSearchHistory(String),
    ClearSearchHistory,
    UpsertRssSource(RssSource),
    DeleteRssSource(i64),
}

/// An acknowledged commit. The revision is local to the current connection owner.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Commit {
    pub revision: u64,
    pub rss_ids: Vec<i64>,
}
