use super::{
    model::{
        ClaimedRun, Commit, Context, Conversation, History, Message, MessagePosition, Mutation,
        NewMessage, Placement, Query, Snapshot,
    },
    schema::{contexts, conversations, messages},
};
use crate::Error;
use diesel::{
    dsl::{max, min},
    prelude::*,
    sql_types::Text,
};

fn conversation(
    connection: &mut SqliteConnection,
    id: &str,
) -> Result<Option<Conversation>, Error> {
    Ok(conversations::table
        .find(id)
        .select(Conversation::as_select())
        .first(connection)
        .optional()?)
}
fn message(
    connection: &mut SqliteConnection,
    conversation_id: &str,
    id: &str,
) -> Result<Option<Message>, Error> {
    Ok(messages::table
        .find((conversation_id, id))
        .select(Message::as_select())
        .first(connection)
        .optional()?)
}
fn context(connection: &mut SqliteConnection, id: &str) -> Result<Option<Context>, Error> {
    Ok(contexts::table
        .find(id)
        .select(Context::as_select())
        .first(connection)
        .optional()?)
}
fn history_messages(
    connection: &mut SqliteConnection,
    id: &str,
    descending: bool,
) -> Result<Vec<Message>, Error> {
    let mut query = messages::table
        .filter(messages::conversation_id.eq(id))
        .into_boxed();
    query = if descending {
        query.order(messages::position.desc())
    } else {
        query.order(messages::position.asc())
    };
    Ok(query.select(Message::as_select()).load(connection)?)
}
fn latest_visible(connection: &mut SqliteConnection, id: &str) -> Result<Option<i64>, Error> {
    Ok(messages::table
        .filter(messages::conversation_id.eq(id))
        .filter(messages::role.ne("System"))
        .select(max(messages::created_at))
        .first(connection)?)
}

pub(crate) fn read(connection: &mut SqliteConnection, query: &Query) -> Result<Snapshot, Error> {
    Ok(match query {
        Query::Conversations => Snapshot::Conversations(diesel::sql_query(
            "SELECT c.conversation_id,c.title,c.title_generated,c.created_at,COALESCE(MAX(m.created_at),c.created_at) AS updated_at,c.is_running,c.run_id,c.error_message,c.retry_json FROM agent_conversations c LEFT JOIN agent_messages m ON m.conversation_id=c.conversation_id AND m.role!='System' GROUP BY c.conversation_id ORDER BY updated_at DESC,c.created_at DESC,c.conversation_id"
        ).load(connection)?),
        Query::Conversation(id) => Snapshot::Conversation(conversation(connection,id)?),
        Query::History(id) => Snapshot::History(match conversation(connection,id)? {
            Some(conversation) => Some(History { conversation, messages: history_messages(connection,id,false)?, context: context(connection,id)? }),
            None => None,
        }),
        Query::Messages {conversation_id,descending} => Snapshot::Messages(history_messages(connection,conversation_id,*descending)?),
        Query::Context(id) => Snapshot::Context(context(connection,id)?),
        Query::LatestMessageByRole {conversation_id,role} => Snapshot::Message(messages::table
            .filter(messages::conversation_id.eq(conversation_id)).filter(messages::role.eq(role))
            .order(messages::position.desc()).select(Message::as_select()).first(connection).optional()?),
        Query::LatestVisibleMessageCreatedAt(id) => Snapshot::Timestamp(latest_visible(connection,id)?),
    })
}

fn require_run(
    connection: &mut SqliteConnection,
    id: &str,
    run: &str,
    active: bool,
) -> Result<Conversation, Error> {
    conversation(connection, id)?
        .filter(|row| row.run_id.as_deref() == Some(run) && (!active || row.is_running))
        .ok_or(Error::StaleRun)
}
fn valid_id(id: &str) -> Result<(), Error> {
    if id.is_empty() {
        Err(Error::InvalidRequest("Agent ID must not be empty".into()))
    } else {
        Ok(())
    }
}
fn json(value: &str, empty_allowed: bool) -> Result<(), Error> {
    if (empty_allowed && value.is_empty())
        || serde_json::from_str::<serde::de::IgnoredAny>(value).is_ok()
    {
        Ok(())
    } else {
        Err(Error::InvalidRequest(
            "Agent payload must contain valid JSON".into(),
        ))
    }
}
fn ensure(
    connection: &mut SqliteConnection,
    id: &str,
    title: Option<&str>,
    now: i64,
) -> Result<(), Error> {
    valid_id(id)?;
    diesel::insert_into(conversations::table)
        .values((
            conversations::conversation_id.eq(id),
            conversations::title.eq(title),
            conversations::created_at.eq(now),
            conversations::updated_at.eq(now),
        ))
        .on_conflict(conversations::conversation_id)
        .do_nothing()
        .execute(connection)?;
    if let Some(title) = title {
        diesel::update(
            conversations::table
                .find(id)
                .filter(conversations::title_generated.eq(false))
                .filter(
                    conversations::title
                        .is_null()
                        .or(conversations::title.eq("")),
                ),
        )
        .set(conversations::title.eq(title))
        .execute(connection)?;
    }
    Ok(())
}

#[derive(QueryableByName)]
struct Token {
    #[diesel(sql_type = Text)]
    value: String,
}

fn claim(
    connection: &mut SqliteConnection,
    id: &str,
    now: i64,
    consume_retry: bool,
    receipt: &mut Commit,
) -> Result<(), Error> {
    valid_id(id)?;
    let existing = conversation(connection, id)?;
    if existing.as_ref().is_some_and(|row| row.is_running) {
        return Err(Error::RunBusy);
    }
    let retry_json = if consume_retry {
        Some(
            existing
                .as_ref()
                .and_then(|row| row.retry_json.clone())
                .ok_or(Error::Conflict)?,
        )
    } else {
        None
    };
    if existing.is_none() {
        ensure(connection, id, None, now)?;
    }
    let run_id = diesel::sql_query("SELECT lower(hex(randomblob(16))) AS value")
        .get_result::<Token>(connection)?
        .value;
    diesel::update(conversations::table.find(id))
        .set((
            conversations::is_running.eq(true),
            conversations::run_id.eq(&run_id),
            conversations::error_message.eq(None::<String>),
            conversations::retry_json.eq(None::<String>),
        ))
        .execute(connection)?;
    receipt.runs.push(ClaimedRun {
        conversation_id: id.into(),
        run_id,
        retry_json,
    });
    Ok(())
}

#[allow(
    clippy::too_many_lines,
    reason = "Exhaustive dispatch of atomic Agent mutations"
)]
pub(crate) fn apply(
    connection: &mut SqliteConnection,
    mutation: Mutation,
    receipt: &mut Commit,
) -> Result<(), Error> {
    match mutation {
        Mutation::EnsureConversation {
            conversation_id,
            title,
            now,
        } => ensure(connection, &conversation_id, title.as_deref(), now)?,
        Mutation::ClaimRun {
            conversation_id,
            now,
        } => claim(connection, &conversation_id, now, false, receipt)?,
        Mutation::ConsumeRetry {
            conversation_id,
            now,
        } => claim(connection, &conversation_id, now, true, receipt)?,
        Mutation::InsertMessages {
            conversation_id,
            run_id,
            placement,
            messages,
        } => insert_messages(
            connection,
            &conversation_id,
            &run_id,
            placement,
            messages,
            receipt,
        )?,
        Mutation::UpdateMessage {
            conversation_id,
            run_id,
            message_id,
            expected_revision,
            text,
            content_json,
            message_json,
        } => {
            require_run(connection, &conversation_id, &run_id, true)?;
            json(&content_json, false)?;
            json(&message_json, true)?;
            let next = expected_revision
                .checked_add(1)
                .ok_or_else(|| Error::InvalidRequest("message revision exhausted".into()))?;
            let changed = diesel::update(
                messages::table
                    .find((&conversation_id, &message_id))
                    .filter(messages::content_revision.eq(expected_revision)),
            )
            .set((
                messages::text.eq(text),
                messages::content_json.eq(content_json),
                messages::message_json.eq(message_json),
                messages::content_revision.eq(next),
            ))
            .execute(connection)?;
            if changed != 1 {
                return Err(Error::Conflict);
            }
        }
        Mutation::SetContext {
            conversation_id,
            run_id,
            through_message_id,
            summary,
        } => {
            require_run(connection, &conversation_id, &run_id, true)?;
            let through = message(connection, &conversation_id, &through_message_id)?
                .ok_or_else(|| Error::InvalidRequest("checkpoint message missing".into()))?;
            if context(connection, &conversation_id)?
                .is_some_and(|previous| previous.through_position > through.position)
            {
                return Err(Error::InvalidRequest(
                    "checkpoint cannot move backwards".into(),
                ));
            }
            let row = Context {
                conversation_id,
                summary,
                through_message_id,
                through_position: through.position,
            };
            diesel::insert_into(contexts::table)
                .values(&row)
                .on_conflict(contexts::conversation_id)
                .do_update()
                .set((
                    contexts::summary.eq(&row.summary),
                    contexts::through_message_id.eq(&row.through_message_id),
                    contexts::through_position.eq(row.through_position),
                ))
                .execute(connection)?;
        }
        Mutation::SetTitle {
            conversation_id,
            run_id,
            title,
            generated,
        } => {
            let existing = if let Some(run) = run_id {
                require_run(connection, &conversation_id, &run, false)?
            } else {
                if generated {
                    return Err(Error::StaleRun);
                }
                conversation(connection, &conversation_id)?.ok_or(Error::StaleRun)?
            };
            if !existing.title_generated {
                diesel::update(conversations::table.find(conversation_id))
                    .set((
                        conversations::title.eq(title),
                        conversations::title_generated.eq(generated),
                    ))
                    .execute(connection)?;
            }
        }
        Mutation::FinishRun {
            conversation_id,
            run_id,
            error_message,
            retry_json,
        } => {
            require_run(connection, &conversation_id, &run_id, true)?;
            if let Some(retry) = &retry_json {
                if error_message.is_none() {
                    return Err(Error::InvalidRequest("retry requires a failure".into()));
                }
                json(retry, false)?;
            }
            diesel::update(conversations::table.find(conversation_id))
                .set((
                    conversations::is_running.eq(false),
                    conversations::error_message.eq(error_message),
                    conversations::retry_json.eq(retry_json),
                ))
                .execute(connection)?;
        }
        Mutation::ClearHistory {
            conversation_id,
            run_id,
        } => {
            let existing = match run_id.as_deref() {
                Some(run) => Some(require_run(connection, &conversation_id, run, true)?),
                None => conversation(connection, &conversation_id)?,
            };
            diesel::delete(contexts::table.find(&conversation_id)).execute(connection)?;
            diesel::delete(messages::table.filter(messages::conversation_id.eq(&conversation_id)))
                .execute(connection)?;
            if run_id.is_none() {
                if let Some(row) = existing.filter(|row| row.is_running) {
                    diesel::update(conversations::table.find(conversation_id))
                        .set((
                            conversations::is_running.eq(false),
                            conversations::run_id.eq(None::<String>),
                            conversations::error_message.eq(None::<String>),
                            conversations::retry_json.eq(None::<String>),
                            conversations::updated_at.eq(row.created_at),
                        ))
                        .execute(connection)?;
                } else {
                    diesel::delete(conversations::table.find(conversation_id))
                        .execute(connection)?;
                }
            }
        }
        Mutation::DeleteConversation(id) => {
            diesel::delete(conversations::table.find(id)).execute(connection)?;
        }
    }
    Ok(())
}

fn insert_messages(
    connection: &mut SqliteConnection,
    id: &str,
    run: &str,
    placement: Placement,
    mut rows: Vec<NewMessage>,
    receipt: &mut Commit,
) -> Result<(), Error> {
    require_run(connection, id, run, true)?;
    let mut cursor: Option<i64> = match placement {
        Placement::Append => messages::table
            .filter(messages::conversation_id.eq(id))
            .select(max(messages::position))
            .first(connection)?,
        Placement::Prepend => messages::table
            .filter(messages::conversation_id.eq(id))
            .select(min(messages::position))
            .first(connection)?,
    };
    if placement == Placement::Prepend {
        rows.reverse();
    }
    let mut positions = Vec::new();
    for row in rows {
        valid_id(&row.message_id)?;
        valid_id(&row.role)?;
        json(&row.content_json, false)?;
        json(&row.message_json, true)?;
        let position = if let Some(previous) = message(connection, id, &row.message_id)? {
            if previous.role != row.role
                || previous.text != row.text
                || previous.content_json != row.content_json
                || previous.message_json != row.message_json
                || previous.created_at != row.created_at
            {
                return Err(Error::Conflict);
            }
            previous.position
        } else {
            let position = match cursor {
                None => 0,
                Some(value) => match placement {
                    Placement::Append => value.checked_add(1),
                    Placement::Prepend => value.checked_sub(1),
                }
                .ok_or_else(|| Error::InvalidRequest("message position exhausted".into()))?,
            };
            cursor = Some(position);
            let record = Message {
                conversation_id: id.into(),
                message_id: row.message_id.clone(),
                position,
                role: row.role,
                text: row.text,
                content_json: row.content_json,
                message_json: row.message_json,
                created_at: row.created_at,
                content_revision: 0,
            };
            diesel::insert_into(messages::table)
                .values(&record)
                .execute(connection)?;
            position
        };
        positions.push(MessagePosition {
            conversation_id: id.into(),
            message_id: row.message_id,
            position,
        });
    }
    if placement == Placement::Prepend {
        positions.reverse();
    }
    receipt.messages.extend(positions);
    let updated_at = latest_visible(connection, id)?;
    if let Some(updated_at) = updated_at {
        diesel::update(conversations::table.find(id))
            .set(conversations::updated_at.eq(updated_at))
            .execute(connection)?;
    }
    Ok(())
}
