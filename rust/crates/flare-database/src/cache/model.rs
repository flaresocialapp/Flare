use super::schema;
use diesel::{AsChangeset, Insertable, Queryable, QueryableByName, Selectable};
use serde::{Deserialize, Serialize};

macro_rules! row {
    ($name:ident,$table:ident,($($pk:ident),+),{$($field:ident:$ty:ty),* $(,)?}) => {
        #[derive(Clone, Default, PartialEq, Eq, Serialize, Deserialize, Queryable, QueryableByName, Selectable, Insertable, AsChangeset)]
        #[diesel(table_name=schema::$table,primary_key($($pk),+),treat_none_as_null=true)]
        pub struct $name { $(pub $field:$ty),* }
        impl std::fmt::Debug for $name {
            fn fmt(&self,f:&mut std::fmt::Formatter<'_>)->std::fmt::Result { f.write_str(concat!(stringify!($name)," { .. }")) }
        }
    };
}

row!(Status,statuses,(id),{id:String,account:String,status_key:String,payload:String,fingerprint:i64,render_hash:i64,text:Option<String>,version:String});
row!(Reference,references,(status_id,reference_type,reference_status_id),{status_id:String,reference_type:String,reference_status_id:String,reference_order:i32});
row!(TimelineRow,timelines,(paging_key,timeline_id),{paging_key:String,timeline_id:String,status_id:String,sort_id:i64,is_gap:bool,message:Option<String>,message_render_hash:Option<i64>,notification_key:Option<String>,content_revision:i64});
row!(PresentationReference,presentations,(paging_key,timeline_id,presentation_type,reference_status_id),{paging_key:String,timeline_id:String,presentation_type:String,reference_status_id:String,reference_order:i32});
row!(PagingKey,paging_keys,(paging_key),{paging_key:String,generation:String,revision:i64,next_key:Option<String>,prev_key:Option<String>,source_heads:String,account_keys:String});
row!(User,users,(user_key),{user_key:String,name:String,canonical_handle:String,host:String,payload:String});
row!(Relation,relations,(account,user_key),{account:String,user_key:String,payload:String});
row!(UserHistory,user_history,(account,user_key),{account:String,user_key:String,last_visit:i64});
row!(Emoji,emojis,(host),{host:String,payload:String});
row!(EmojiHistory,emoji_history,(account,short_code),{account:String,short_code:String,last_use:i64});
row!(List,lists,(id),{id:String,account:String,list_key:String,payload:String});
row!(ListPage,list_pages,(paging_key,account,list_key),{paging_key:String,account:String,list_key:String,position:i64});
#[derive(
    Clone,
    Debug,
    Default,
    PartialEq,
    Eq,
    Serialize,
    Deserialize,
    Queryable,
    QueryableByName,
    Selectable,
    Insertable,
)]
#[diesel(table_name=schema::list_members)]
pub struct ListMember {
    pub list_key: String,
    pub member_key: String,
}
row!(RoomTimeline,room_timelines,(account,room_key),{account:String,room_key:String,sort_id:i64,unread_count:i64,payload:String});
row!(Room,rooms,(room_key),{room_key:String,platform_id:String,last_message_key:Option<String>});
#[derive(
    Clone,
    Debug,
    Default,
    PartialEq,
    Eq,
    Serialize,
    Deserialize,
    Queryable,
    QueryableByName,
    Selectable,
    Insertable,
)]
#[diesel(table_name=schema::room_members)]
pub struct RoomMember {
    pub room_key: String,
    pub user_key: String,
}
row!(Message,messages,(message_key),{message_key:String,room_key:String,user_key:String,timestamp:i64,payload:String,show_sender:bool,is_local:bool,remote_cursor:Option<String>,revision:i64});
row!(Translation,translations,(id),{id:String,entity_type:String,entity_key:String,target_language:String,source_hash:String,status:String,display_mode:String,payload:Option<String>,status_reason:Option<String>,attempt_count:i64,updated_at:i64});

/// Opaque, deterministic IDs use UTF-8 byte lengths on every Rust target.
#[must_use]
pub fn key(parts: &[&str]) -> String {
    use std::fmt::Write as _;
    let mut key = String::new();
    for part in parts {
        let _ = write!(key, "{}:{part}", part.len());
    }
    key
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Info {
    pub epoch: String,
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct PageGuard {
    pub generation: String,
    pub revision: i64,
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize, QueryableByName)]
pub struct PageVersion {
    #[diesel(sql_type=diesel::sql_types::Text)]
    pub paging_key: String,
    #[diesel(sql_type=diesel::sql_types::Text)]
    pub generation: String,
    #[diesel(sql_type=diesel::sql_types::BigInt)]
    pub revision: i64,
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Range {
    pub offset: i64,
    pub limit: i64,
    pub descending: bool,
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize, Queryable, QueryableByName)]
pub struct Identity {
    #[diesel(sql_type=diesel::sql_types::Text)]
    pub timeline_id: String,
    #[diesel(sql_type=diesel::sql_types::Text)]
    pub status_id: String,
    #[diesel(sql_type=diesel::sql_types::BigInt)]
    pub sort_id: i64,
    #[diesel(sql_type=diesel::sql_types::BigInt)]
    pub content_revision: i64,
    #[diesel(sql_type=diesel::sql_types::Nullable<diesel::sql_types::BigInt>)]
    pub message_render_hash: Option<i64>,
}
#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Page {
    pub info: Info,
    pub metadata: Option<PagingKey>,
    pub rows: Vec<TimelineRow>,
    pub statuses: Vec<Status>,
    pub references: Vec<Reference>,
    pub presentations: Vec<PresentationReference>,
    pub translations: Vec<Translation>,
    pub has_more: bool,
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct TimelineCursor {
    pub status_id: String,
    pub paging_key: String,
    pub timeline_id: String,
}
#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct TimelineScan {
    pub info: Info,
    /// Hydrated fragments, grouped by paging key; each carries its structural write guard.
    pub pages: Vec<Page>,
    pub next: Option<TimelineCursor>,
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct IdentityPage {
    pub info: Info,
    pub metadata: Option<PageVersion>,
    pub rows: Vec<Identity>,
    pub has_more: bool,
}
#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct StatusBundle {
    pub statuses: Vec<Status>,
    pub references: Vec<Reference>,
    pub translations: Vec<Translation>,
}
#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct StatusPage {
    pub root_ids: Vec<String>,
    pub bundle: StatusBundle,
    pub has_more: bool,
}
#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Joined {
    pub records: Vec<Record>,
    pub users: Vec<User>,
    pub messages: Vec<Message>,
}
#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RecordPage {
    pub records: Vec<Record>,
    pub users: Vec<User>,
    pub has_more: bool,
}

#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum Record {
    Status(Status),
    Reference(Reference),
    Timeline(TimelineRow),
    Presentation(PresentationReference),
    PagingKey(PagingKey),
    User(User),
    Relation(Relation),
    UserHistory(UserHistory),
    Emoji(Emoji),
    EmojiHistory(EmojiHistory),
    List(List),
    ListPage(ListPage),
    ListMember(ListMember),
    RoomTimeline(RoomTimeline),
    Room(Room),
    RoomMember(RoomMember),
    Message(Message),
    Translation(Translation),
}
#[derive(Clone, Serialize, Deserialize)]
pub enum Query {
    Info,
    TimelinePage {
        paging_key: String,
        range: Range,
    },
    TimelineIdentities {
        paging_key: String,
        range: Range,
    },
    TimelineItems {
        paging_key: String,
        timeline_ids: Vec<String>,
    },
    AccountTimelines {
        account: String,
        after: Option<TimelineCursor>,
        limit: i64,
    },
    PagingKey(String),
    SizeThrough {
        paging_key: String,
        timeline_id: String,
    },
    NextRow {
        paging_key: String,
        timeline_id: String,
    },
    Statuses(Vec<String>),
    Status(String),
    References(Vec<String>),
    SearchStatuses {
        text: String,
        range: Range,
    },
    Users(Vec<String>),
    UserByHandle {
        canonical_handle: String,
        host: String,
    },
    SearchUsers {
        text: String,
        range: Range,
    },
    UserHistory {
        account: Option<String>,
        range: Range,
    },
    Relation {
        account: String,
        user_key: String,
    },
    Emoji(String),
    EmojiHistory {
        account: String,
        range: Range,
    },
    List {
        account: String,
        list_key: String,
    },
    Lists {
        paging_key: String,
        range: Range,
    },
    ListMembers {
        list_key: String,
        range: Range,
    },
    ListMemberKeys {
        list_key: String,
        range: Range,
    },
    ListsForUser {
        account: String,
        user_key: String,
    },
    Rooms {
        account: String,
        range: Range,
    },
    Room {
        account: String,
        room_key: String,
    },
    RoomMessages {
        room_key: String,
        range: Range,
    },
    Message(String),
    LatestRemoteMessage(String),
    Translations {
        entity_type: String,
        entity_keys: Vec<String>,
        language: Option<String>,
    },
    Stats,
}
#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum Snapshot {
    Info(Info),
    Page(Page),
    TimelineScan(TimelineScan),
    Identities(IdentityPage),
    StatusBundle(StatusBundle),
    StatusPage(StatusPage),
    Status(Option<Status>),
    Message(Option<Message>),
    RecordsPage(RecordPage),
    Records(Vec<Record>),
    Joined(Joined),
    Count(i64),
    Stats(Vec<(String, i64)>),
}

#[derive(Clone, Serialize, Deserialize)]
pub struct ReferenceSet {
    pub status_id: String,
    pub references: Vec<Reference>,
}
#[derive(Clone, Serialize, Deserialize)]
pub struct PresentationSet {
    pub timeline_id: String,
    pub references: Vec<PresentationReference>,
}
#[derive(Clone, Serialize, Deserialize)]
pub struct PageWrite {
    pub paging_key: String,
    pub expected: PageGuard,
    pub statuses: Vec<Status>,
    pub references: Vec<ReferenceSet>,
    pub items: Vec<TimelineRow>,
    pub presentations: Vec<PresentationSet>,
    pub remove_ids: Vec<String>,
    pub next_key: Option<String>,
    pub keep_next_key: bool,
    pub prev_key: Option<String>,
    pub source_heads: String,
    pub account_keys: Vec<String>,
}
#[derive(Clone, Serialize, Deserialize)]
pub struct TranslationResult {
    pub id: String,
    pub source_hash: String,
    pub attempt_count: i64,
    pub status: String,
    pub payload: Option<String>,
    pub reason: Option<String>,
    pub updated_at: i64,
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum Delete {
    Status(String),
    References(String),
    User(String),
    UserHistory {
        account: String,
        user_key: Option<String>,
    },
    Relation {
        account: String,
        user_key: String,
    },
    Emoji(String),
    EmojiHistory(String),
    List {
        account: String,
        list_key: String,
    },
    ListPage(String),
    ListMember {
        list_key: String,
        member_key: String,
    },
    Room {
        account: String,
        room_key: String,
    },
    Message(String),
    Translation {
        entity_type: String,
        entity_key: String,
        language: String,
    },
}
#[derive(Clone, Serialize, Deserialize)]
pub enum Mutation {
    Put(Record),
    /// Update an existing status only. The version comes from the last read, not the network.
    UpdateStatus {
        expected_version: String,
        status: Status,
    },
    Delete(Delete),
    ResetTimeline {
        paging_key: String,
        account_keys: Vec<String>,
    },
    CommitPage(PageWrite),
    ReplaceReferences(ReferenceSet),
    ReplaceListMembers {
        list_key: String,
        member_keys: Vec<String>,
    },
    ReplaceRoomMembers {
        room_key: String,
        user_keys: Vec<String>,
    },
    /// Atomically replace all cached messages in this room; an empty list clears them.
    ReplaceRoomMessages {
        room_key: String,
        messages: Vec<Message>,
    },
    TranslationResult(TranslationResult),
    /// Claim only a matching Pending attempt; a duplicate claim conflicts.
    StartTranslation {
        id: String,
        source_hash: String,
        attempt_count: i64,
        updated_at: i64,
    },
    TranslationDisplay {
        id: String,
        display_mode: String,
        updated_at: i64,
    },
    FailStaleTranslations {
        before: i64,
        reason: String,
        updated_at: i64,
    },
    DeleteInFlightTranslations,
    UpdateLocalMessage {
        message_key: String,
        expected_revision: i64,
        payload: String,
    },
    AcknowledgeMessage {
        local_key: String,
        expected_revision: i64,
        remote: Message,
    },
    ClearUnread {
        account: String,
        room_key: String,
    },
    ClearAccount(String),
    ResetCache,
}
#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Commit {
    pub revision: u64,
    pub epoch: String,
    pub pages: Vec<PagingKey>,
}

macro_rules! redacted_debug {($($ty:ty),+) => {$ (
    impl std::fmt::Debug for $ty {fn fmt(&self,f:&mut std::fmt::Formatter<'_>)->std::fmt::Result {f.write_str(concat!(stringify!($ty)," { .. }"))}}
)+};}
redacted_debug!(
    Page,
    TimelineScan,
    StatusBundle,
    StatusPage,
    Joined,
    RecordPage,
    Record,
    Query,
    Snapshot,
    Commit
);
