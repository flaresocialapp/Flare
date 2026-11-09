use super::schema::{contexts, conversations, messages};
use diesel::{AsChangeset, Insertable, Queryable, QueryableByName, Selectable};
use serde::{Deserialize, Serialize};

#[derive(Clone, PartialEq, Eq, Serialize, Deserialize, Queryable, QueryableByName, Selectable)]
#[diesel(table_name = conversations)]
pub struct Conversation {
    pub conversation_id: String,
    pub title: Option<String>,
    pub title_generated: bool,
    pub created_at: i64,
    pub updated_at: i64,
    pub is_running: bool,
    /// The latest run's fence, retained after completion for asynchronous title generation.
    pub run_id: Option<String>,
    pub error_message: Option<String>,
    pub retry_json: Option<String>,
}

#[derive(
    Clone, PartialEq, Eq, Serialize, Deserialize, Queryable, Selectable, Insertable, AsChangeset,
)]
#[diesel(table_name = messages, primary_key(conversation_id, message_id))]
pub struct Message {
    pub conversation_id: String,
    pub message_id: String,
    pub position: i64,
    pub role: String,
    pub text: String,
    pub content_json: String,
    pub message_json: String,
    pub created_at: i64,
    pub content_revision: i64,
}

#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct NewMessage {
    pub message_id: String,
    pub role: String,
    pub text: String,
    pub content_json: String,
    /// Empty for a UI-only message; otherwise valid opaque JSON.
    pub message_json: String,
    pub created_at: i64,
}

#[derive(Clone, PartialEq, Eq, Serialize, Deserialize, Queryable, Selectable, Insertable)]
#[diesel(table_name = contexts)]
pub struct Context {
    pub conversation_id: String,
    pub summary: String,
    pub through_message_id: String,
    pub through_position: i64,
}

#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct History {
    pub conversation: Conversation,
    pub messages: Vec<Message>,
    pub context: Option<Context>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum Query {
    Conversations,
    Conversation(String),
    History(String),
    Messages {
        conversation_id: String,
        descending: bool,
    },
    Context(String),
    LatestMessageByRole {
        conversation_id: String,
        role: String,
    },
    LatestVisibleMessageCreatedAt(String),
}

#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum Snapshot {
    Conversations(Vec<Conversation>),
    Conversation(Option<Conversation>),
    History(Option<History>),
    Messages(Vec<Message>),
    Context(Option<Context>),
    Message(Option<Message>),
    Timestamp(Option<i64>),
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum Placement {
    Append,
    Prepend,
}

#[derive(Clone, Serialize, Deserialize)]
pub enum Mutation {
    EnsureConversation {
        conversation_id: String,
        title: Option<String>,
        now: i64,
    },
    /// Atomically claim an idle conversation and return a fresh database-generated run ID.
    ClaimRun {
        conversation_id: String,
        now: i64,
    },
    /// Claim a run and remove its persisted retry payload before tools can be invoked.
    ConsumeRetry {
        conversation_id: String,
        now: i64,
    },
    InsertMessages {
        conversation_id: String,
        run_id: String,
        placement: Placement,
        messages: Vec<NewMessage>,
    },
    UpdateMessage {
        conversation_id: String,
        run_id: String,
        message_id: String,
        expected_revision: i64,
        text: String,
        content_json: String,
        message_json: String,
    },
    SetContext {
        conversation_id: String,
        run_id: String,
        through_message_id: String,
        summary: String,
    },
    /// Asynchronous title results must carry their run ID; direct user fallback titles may omit it.
    SetTitle {
        conversation_id: String,
        run_id: Option<String>,
        title: String,
        generated: bool,
    },
    FinishRun {
        conversation_id: String,
        run_id: String,
        error_message: Option<String>,
        retry_json: Option<String>,
    },
    /// With a run ID, reset that active run's history while keeping its lease.
    /// Without one, invalidate the old run; retain its row if it was running, otherwise delete it.
    ClearHistory {
        conversation_id: String,
        run_id: Option<String>,
    },
    DeleteConversation(String),
}

#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ClaimedRun {
    pub conversation_id: String,
    pub run_id: String,
    pub retry_json: Option<String>,
}
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct MessagePosition {
    pub conversation_id: String,
    pub message_id: String,
    pub position: i64,
}
#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Commit {
    pub revision: u64,
    pub runs: Vec<ClaimedRun>,
    pub messages: Vec<MessagePosition>,
}

// Conversation content, prompts, summaries and retry payloads must not reach debug logs.
macro_rules! redacted_debug {
    ($($value:ty),+ $(,)?) => {$ (
        impl std::fmt::Debug for $value {
            fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
                formatter.write_str(concat!(stringify!($value), " { .. }"))
            }
        }
    )+};
}
redacted_debug!(
    Conversation,
    Message,
    NewMessage,
    Context,
    History,
    Snapshot,
    ClaimedRun,
    Commit
);
