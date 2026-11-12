use super::read::keys;
use super::{Change, Changes, TextValue, execute, info, invalid, json, metadata, nonempty, select};
use crate::{
    Error,
    cache::{
        Commit, Delete, Emoji, EmojiHistory, List, ListMember, ListPage, Message, Mutation,
        PageWrite, PagingKey, PresentationReference, Record, Reference, ReferenceSet, Relation,
        Room, RoomMember, RoomTimeline, Status, TimelineRow, Translation, User, UserHistory, key,
        schema::{
            emoji_history, emojis, list_members, list_pages, lists, messages, paging_keys,
            presentations, references, relations, room_members, room_timelines, rooms, statuses,
            timelines, translations, user_history, users,
        },
    },
};
use diesel::{connection::SimpleConnection, prelude::*};
use std::collections::BTreeSet;

fn canonical_id(id: &mut String, expected: String) -> Result<(), Error> {
    if !id.is_empty() && *id != expected {
        return Err(invalid("cache identity does not match its scope"));
    }
    *id = expected;
    Ok(())
}
fn fingerprint(payload: &str) -> i64 {
    let mut hash = 0xcbf2_9ce4_8422_2325_u64;
    for b in payload.bytes() {
        hash ^= u64::from(b);
        hash = hash.wrapping_mul(0x0000_0100_0000_01b3);
    }
    i64::from_le_bytes(hash.to_le_bytes())
}
fn normalize_status(row: &mut Status) -> Result<(), Error> {
    nonempty(&row.account)?;
    nonempty(&row.status_key)?;
    json(&row.payload)?;
    canonical_id(&mut row.id, key(&[&row.account, &row.status_key]))?;
    row.fingerprint = fingerprint(&row.payload);
    Ok(())
}
fn status_version(c: &mut SqliteConnection) -> Result<String, Error> {
    Ok(
        select::<TextValue>(c, "SELECT lower(hex(randomblob(16))) AS value", &[])?
            .remove(0)
            .value,
    )
}
fn update_status(c: &mut SqliteConnection, expected: &str, mut row: Status) -> Result<(), Error> {
    normalize_status(&mut row)?;
    let old = statuses::table
        .find(&row.id)
        .filter(statuses::version.eq(expected))
        .select(Status::as_select())
        .first::<Status>(c)
        .optional()?
        .ok_or(Error::Conflict)?;
    row.version.clone_from(&old.version);
    if old != row {
        row.version = status_version(c)?;
        let changed = diesel::update(
            statuses::table
                .find(&row.id)
                .filter(statuses::version.eq(expected)),
        )
        .set(&row)
        .execute(c)?;
        if changed == 0 {
            return Err(Error::Conflict);
        }
    }
    Ok(())
}
fn accounts(mut values: Vec<String>) -> Result<String, Error> {
    for v in &values {
        nonempty(v)?;
    }
    values.sort();
    values.dedup();
    serde_json::to_string(&values).map_err(|_| invalid("invalid account keys"))
}
fn translation_state(value: &str) -> Result<(), Error> {
    if ["Pending", "Translating", "Completed", "Failed", "Skipped"].contains(&value) {
        Ok(())
    } else {
        Err(invalid("unknown translation state"))
    }
}
fn display(value: &str) -> Result<(), Error> {
    if ["Auto", "Original", "Translated"].contains(&value) {
        Ok(())
    } else {
        Err(invalid("unknown translation display mode"))
    }
}
fn normalize_translation(row: &mut Translation) -> Result<(), Error> {
    if !["Status", "Profile"].contains(&row.entity_type.as_str()) {
        return Err(invalid("unknown translation entity"));
    }
    nonempty(&row.entity_key)?;
    nonempty(&row.target_language)?;
    nonempty(&row.source_hash)?;
    translation_state(&row.status)?;
    display(&row.display_mode)?;
    if row.attempt_count < 0 {
        return Err(invalid("negative translation attempt"));
    }
    if let Some(p) = &row.payload {
        json(p)?;
    }
    canonical_id(
        &mut row.id,
        key(&[&row.entity_type, &row.entity_key, &row.target_language]),
    )
}

macro_rules! save {
 ($c:expr,$row:expr,$ty:ident,$table:ident,($($pk:ident),+))=>{{
  let row=$row;
  let sql=concat!("SELECT * FROM cache_",stringify!($table)," WHERE ",$(stringify!($pk),"=? AND ",)+"1=1");
  let old=select::<$ty>($c,sql,&[$(row.$pk.as_str()),+])?;
  if old.first()!=Some(row) {
   diesel::insert_into($table::table).values(row).on_conflict(($($table::$pk,)+)).do_update().set(row).execute($c)?;
  }
 }};
}

#[allow(
    clippy::too_many_lines,
    reason = "Exhaustive row validation and upsert for the 18 cache entities"
)]
fn put(c: &mut SqliteConnection, record: Record) -> Result<(), Error> {
    match record {
        Record::Status(mut r) => {
            normalize_status(&mut r)?;
            let old = statuses::table
                .find(&r.id)
                .select(Status::as_select())
                .first::<Status>(c)
                .optional()?;
            r.version = old.as_ref().map_or_else(String::new, |r| r.version.clone());
            if old.as_ref() != Some(&r) {
                r.version = status_version(c)?;
                diesel::insert_into(statuses::table)
                    .values(&r)
                    .on_conflict(statuses::id)
                    .do_update()
                    .set(&r)
                    .execute(c)?;
            }
        }
        Record::Reference(r) => {
            nonempty(&r.reference_type)?;
            nonempty(&r.reference_status_id)?;
            save!(
                c,
                &r,
                Reference,
                references,
                (status_id, reference_type, reference_status_id)
            );
        }
        Record::Timeline(_) | Record::PagingKey(_) | Record::Presentation(_) => {
            return Err(invalid("timeline rows require ResetTimeline or CommitPage"));
        }
        Record::User(r) => {
            nonempty(&r.user_key)?;
            json(&r.payload)?;
            save!(c, &r, User, users, (user_key));
        }
        Record::Relation(r) => {
            nonempty(&r.account)?;
            nonempty(&r.user_key)?;
            json(&r.payload)?;
            save!(c, &r, Relation, relations, (account, user_key));
        }
        Record::UserHistory(r) => {
            nonempty(&r.account)?;
            nonempty(&r.user_key)?;
            save!(c, &r, UserHistory, user_history, (account, user_key));
        }
        Record::Emoji(r) => {
            nonempty(&r.host)?;
            json(&r.payload)?;
            save!(c, &r, Emoji, emojis, (host));
        }
        Record::EmojiHistory(r) => {
            nonempty(&r.account)?;
            nonempty(&r.short_code)?;
            save!(c, &r, EmojiHistory, emoji_history, (account, short_code));
        }
        Record::List(mut r) => {
            nonempty(&r.account)?;
            nonempty(&r.list_key)?;
            json(&r.payload)?;
            canonical_id(&mut r.id, key(&[&r.account, &r.list_key]))?;
            save!(c, &r, List, lists, (id));
        }
        Record::ListPage(r) => {
            nonempty(&r.paging_key)?;
            nonempty(&r.account)?;
            nonempty(&r.list_key)?;
            save!(c, &r, ListPage, list_pages, (paging_key, account, list_key));
        }
        Record::ListMember(r) => {
            nonempty(&r.list_key)?;
            nonempty(&r.member_key)?;
            diesel::insert_into(list_members::table)
                .values(r)
                .on_conflict_do_nothing()
                .execute(c)?;
        }
        Record::RoomTimeline(r) => {
            nonempty(&r.account)?;
            nonempty(&r.room_key)?;
            json(&r.payload)?;
            save!(c, &r, RoomTimeline, room_timelines, (account, room_key));
            recompute_room(c, &r.room_key, false)?;
        }
        Record::Room(mut r) => {
            nonempty(&r.room_key)?;
            r.last_message_key = None;
            save!(c, &r, Room, rooms, (room_key));
            recompute_room(c, &r.room_key, false)?;
        }
        Record::RoomMember(r) => {
            nonempty(&r.room_key)?;
            nonempty(&r.user_key)?;
            diesel::insert_into(room_members::table)
                .values(r)
                .on_conflict_do_nothing()
                .execute(c)?;
        }
        Record::Message(mut r) => {
            nonempty(&r.message_key)?;
            nonempty(&r.room_key)?;
            nonempty(&r.user_key)?;
            json(&r.payload)?;
            let old = select::<Message>(
                c,
                "SELECT * FROM cache_messages WHERE message_key=?",
                &[&r.message_key],
            )?
            .pop();
            let corrected_time = old.as_ref().is_some_and(|old| old.timestamp != r.timestamp);
            if let Some(old) = old {
                if old.room_key != r.room_key || old.is_local != r.is_local {
                    return Err(Error::Conflict);
                }
                r.revision = old.revision;
                if old != r {
                    if old.is_local {
                        return Err(Error::Conflict);
                    }
                    r.revision = r.revision.checked_add(1).ok_or(Error::Conflict)?;
                }
            } else {
                r.revision = 0;
            }
            save!(c, &r, Message, messages, (message_key));
            recompute_room(c, &r.room_key, corrected_time)?;
        }
        Record::Translation(mut r) => {
            normalize_translation(&mut r)?;
            save!(c, &r, Translation, translations, (id));
        }
    }
    Ok(())
}

fn recompute_room(c: &mut SqliteConnection, room: &str, reset_order: bool) -> Result<(), Error> {
    execute(
        c,
        "UPDATE cache_rooms SET last_message_key=(SELECT message_key FROM cache_messages WHERE room_key=? AND is_local=0 ORDER BY timestamp DESC,message_key DESC LIMIT 1) WHERE room_key=?",
        &[room, room],
    )?;
    // Backfill preserves a server-advertised position; removal and refresh can move it back.
    let sql = if reset_order {
        "UPDATE cache_room_timelines SET sort_id=coalesce((SELECT MAX(timestamp) FROM cache_messages WHERE room_key=? AND is_local=0),0) WHERE room_key=?"
    } else {
        "UPDATE cache_room_timelines SET sort_id=max(sort_id,coalesce((SELECT MAX(timestamp) FROM cache_messages WHERE room_key=? AND is_local=0),sort_id)) WHERE room_key=?"
    };
    execute(c, sql, &[room, room])?;
    Ok(())
}
fn orphan_room(c: &mut SqliteConnection, room: &str) -> Result<(), Error> {
    for table in ["cache_messages", "cache_room_members", "cache_rooms"] {
        execute(
            c,
            &format!(
                "DELETE FROM {table} WHERE room_key=? AND NOT EXISTS(SELECT 1 FROM cache_room_timelines t WHERE t.room_key=?)"
            ),
            &[room, room],
        )?;
    }
    Ok(())
}
fn orphan_list(c: &mut SqliteConnection, list: &str) -> Result<(), Error> {
    execute(
        c,
        "DELETE FROM cache_list_members WHERE list_key=? AND NOT EXISTS(SELECT 1 FROM cache_lists l WHERE l.list_key=?) AND NOT EXISTS(SELECT 1 FROM cache_list_pages p WHERE p.list_key=?)",
        &[list, list, list],
    )?;
    Ok(())
}
#[allow(
    clippy::too_many_lines,
    reason = "Exhaustive delete dispatch for cache entities"
)]
fn delete(c: &mut SqliteConnection, what: Delete) -> Result<(), Error> {
    match what {
        Delete::Status(id) => {
            execute(
                c,
                "DELETE FROM cache_translations WHERE entity_type='Status' AND entity_key=?",
                &[&id],
            )?;
            execute(c, "DELETE FROM cache_statuses WHERE id=?", &[&id])?;
        }
        Delete::References(id) => {
            execute(c, "DELETE FROM cache_references WHERE status_id=?", &[&id])?;
        }
        Delete::User(id) => {
            execute(c, "DELETE FROM cache_users WHERE user_key=?", &[&id])?;
        }
        Delete::UserHistory { account, user_key } => {
            if let Some(key) = user_key {
                execute(
                    c,
                    "DELETE FROM cache_user_history WHERE account=? AND user_key=?",
                    &[&account, &key],
                )?;
            } else {
                execute(
                    c,
                    "DELETE FROM cache_user_history WHERE account=?",
                    &[&account],
                )?;
            }
        }
        Delete::Relation { account, user_key } => {
            execute(
                c,
                "DELETE FROM cache_relations WHERE account=? AND user_key=?",
                &[&account, &user_key],
            )?;
        }
        Delete::Emoji(host) => {
            execute(c, "DELETE FROM cache_emojis WHERE host=?", &[&host])?;
        }
        Delete::EmojiHistory(account) => {
            execute(
                c,
                "DELETE FROM cache_emoji_history WHERE account=?",
                &[&account],
            )?;
        }
        Delete::List { account, list_key } => {
            execute(
                c,
                "DELETE FROM cache_list_pages WHERE account=? AND list_key=?",
                &[&account, &list_key],
            )?;
            execute(
                c,
                "DELETE FROM cache_lists WHERE account=? AND list_key=?",
                &[&account, &list_key],
            )?;
            orphan_list(c, &list_key)?;
        }
        Delete::ListPage(pkey) => {
            execute(
                c,
                "DELETE FROM cache_list_pages WHERE paging_key=?",
                &[&pkey],
            )?;
        }
        Delete::ListMember {
            list_key,
            member_key,
        } => {
            execute(
                c,
                "DELETE FROM cache_list_members WHERE list_key=? AND member_key=?",
                &[&list_key, &member_key],
            )?;
        }
        Delete::Room { account, room_key } => {
            execute(
                c,
                "DELETE FROM cache_room_timelines WHERE account=? AND room_key=?",
                &[&account, &room_key],
            )?;
            orphan_room(c, &room_key)?;
        }
        Delete::Message(id) => {
            let old = select::<Message>(
                c,
                "SELECT * FROM cache_messages WHERE message_key=?",
                &[&id],
            )?
            .pop();
            execute(c, "DELETE FROM cache_messages WHERE message_key=?", &[&id])?;
            if let Some(old) = old {
                recompute_room(c, &old.room_key, !old.is_local)?;
            }
        }
        Delete::Translation {
            entity_type,
            entity_key,
            language,
        } => {
            execute(
                c,
                "DELETE FROM cache_translations WHERE entity_type=? AND entity_key=? AND target_language=?",
                &[&entity_type, &entity_key, &language],
            )?;
        }
    }
    Ok(())
}

fn references(c: &mut SqliteConnection, set: ReferenceSet) -> Result<(), Error> {
    nonempty(&set.status_id)?;
    let mut rows = set.references;
    if rows.iter().any(|r| r.status_id != set.status_id) {
        return Err(invalid("semantic reference owner mismatch"));
    }
    rows.sort_by(|a, b| {
        (&a.reference_type, &a.reference_status_id)
            .cmp(&(&b.reference_type, &b.reference_status_id))
    });
    if rows.windows(2).any(|p| {
        p[0].reference_type == p[1].reference_type
            && p[0].reference_status_id == p[1].reference_status_id
    }) {
        return Err(invalid("duplicate semantic reference"));
    }
    let old = select::<Reference>(
        c,
        "SELECT * FROM cache_references WHERE status_id=? ORDER BY reference_type,reference_status_id",
        &[&set.status_id],
    )?;
    if old != rows {
        execute(
            c,
            "DELETE FROM cache_references WHERE status_id=?",
            &[&set.status_id],
        )?;
        for r in rows {
            put(c, Record::Reference(r))?;
        }
    }
    Ok(())
}
fn reset(c: &mut SqliteConnection, pkey: &str, account_keys: Vec<String>) -> Result<(), Error> {
    nonempty(pkey)?;
    let account_keys = accounts(account_keys)?;
    execute(
        c,
        "DELETE FROM cache_paging_keys WHERE paging_key=?",
        &[pkey],
    )?;
    execute(
        c,
        "INSERT INTO cache_paging_keys VALUES(?,lower(hex(randomblob(16))),0,NULL,NULL,'{}',?)",
        &[pkey, &account_keys],
    )?;
    Ok(())
}
#[allow(clippy::too_many_lines, reason = "One guarded atomic timeline page")]
fn page(c: &mut SqliteConnection, p: PageWrite) -> Result<(), Error> {
    let old = metadata(c, &p.paging_key)?.ok_or(Error::Conflict)?;
    if old.generation != p.expected.generation || old.revision != p.expected.revision {
        return Err(Error::Conflict);
    }
    json(&p.source_heads)?;
    let account_keys = accounts(p.account_keys)?;
    for r in p.statuses {
        put(c, Record::Status(r))?;
    }
    for r in p.references {
        references(c, r)?;
    }
    let mut seen = BTreeSet::new();
    for mut row in p.items {
        if row.paging_key != p.paging_key || !seen.insert(row.timeline_id.clone()) {
            return Err(invalid("page row scope or identity mismatch"));
        }
        nonempty(&row.timeline_id)?;
        let current = select::<TimelineRow>(
            c,
            "SELECT * FROM cache_timelines WHERE paging_key=? AND timeline_id=?",
            &[&p.paging_key, &row.timeline_id],
        )?
        .pop();
        row.content_revision = current.as_ref().map_or(0, |r| r.content_revision);
        if let Some(old) = &current
            && old != &row
        {
            row.content_revision = row.content_revision.checked_add(1).ok_or(Error::Conflict)?;
        }
        save!(c, &row, TimelineRow, timelines, (paging_key, timeline_id));
    }
    for id in p.remove_ids {
        execute(
            c,
            "DELETE FROM cache_timelines WHERE paging_key=? AND timeline_id=?",
            &[&p.paging_key, &id],
        )?;
    }
    for set in p.presentations {
        let mut refs = set.references;
        if refs
            .iter()
            .any(|r| r.paging_key != p.paging_key || r.timeline_id != set.timeline_id)
        {
            return Err(invalid("presentation owner mismatch"));
        }
        refs.sort_by(|a, b| {
            (&a.presentation_type, &a.reference_status_id)
                .cmp(&(&b.presentation_type, &b.reference_status_id))
        });
        if refs.windows(2).any(|p| {
            p[0].presentation_type == p[1].presentation_type
                && p[0].reference_status_id == p[1].reference_status_id
        }) {
            return Err(invalid("duplicate presentation reference"));
        }
        let old = select::<PresentationReference>(
            c,
            "SELECT * FROM cache_presentations WHERE paging_key=? AND timeline_id=? ORDER BY presentation_type,reference_status_id",
            &[&p.paging_key, &set.timeline_id],
        )?;
        if refs != old {
            execute(
                c,
                "DELETE FROM cache_presentations WHERE paging_key=? AND timeline_id=?",
                &[&p.paging_key, &set.timeline_id],
            )?;
            for r in refs {
                nonempty(&r.presentation_type)?;
                nonempty(&r.reference_status_id)?;
                save!(
                    c,
                    &r,
                    PresentationReference,
                    presentations,
                    (
                        paging_key,
                        timeline_id,
                        presentation_type,
                        reference_status_id
                    )
                );
            }
        }
    }
    let next = if p.keep_next_key {
        old.next_key
    } else {
        p.next_key
    };
    diesel::update(paging_keys::table.find(&p.paging_key))
        .set((
            paging_keys::next_key.eq(next),
            paging_keys::prev_key.eq(p.prev_key),
            paging_keys::source_heads.eq(p.source_heads),
            paging_keys::account_keys.eq(account_keys),
        ))
        .execute(c)?;
    // Every accepted response consumes its guard, even if the page is identical.
    execute(
        c,
        "INSERT INTO cache_changes VALUES('timeline',?,1) ON CONFLICT(topic,entry) DO UPDATE SET structure=1",
        &[&p.paging_key],
    )?;
    Ok(())
}

fn clear_account(c: &mut SqliteConnection, account: &str) -> Result<(), Error> {
    let rooms = keys(
        c,
        "SELECT room_key AS value FROM cache_room_timelines WHERE account=?",
        &[account],
    )?;
    let lists = keys(
        c,
        "SELECT list_key AS value FROM cache_lists WHERE account=? UNION SELECT list_key AS value FROM cache_list_pages WHERE account=?",
        &[account, account],
    )?;
    let pages = select::<PagingKey>(c, "SELECT * FROM cache_paging_keys", &[])?;
    for page in pages {
        let keys: Vec<String> = serde_json::from_str(&page.account_keys)
            .map_err(|_| Error::Schema("invalid cache account keys".into()))?;
        if keys.iter().any(|a| a == account) {
            execute(
                c,
                "DELETE FROM cache_paging_keys WHERE paging_key=?",
                &[&page.paging_key],
            )?;
        }
    }
    execute(
        c,
        "DELETE FROM cache_translations WHERE entity_type='Status' AND entity_key IN(SELECT id FROM cache_statuses WHERE account=?)",
        &[account],
    )?;
    for table in [
        "cache_statuses",
        "cache_relations",
        "cache_user_history",
        "cache_emoji_history",
        "cache_lists",
        "cache_list_pages",
        "cache_room_timelines",
    ] {
        execute(
            c,
            &format!("DELETE FROM {table} WHERE account=?"),
            &[account],
        )?;
    }
    for room in rooms {
        orphan_room(c, &room)?;
    }
    for list in lists {
        orphan_list(c, &list)?;
    }
    Ok(())
}
fn ack(
    c: &mut SqliteConnection,
    local: &str,
    revision: i64,
    mut remote: Message,
) -> Result<(), Error> {
    if remote.is_local || remote.message_key == local {
        return Err(invalid("ACK requires a distinct remote message"));
    }
    json(&remote.payload)?;
    let old = select::<Message>(
        c,
        "SELECT * FROM cache_messages WHERE message_key=?",
        &[local],
    )?
    .pop();
    if let Some(old) = old {
        if !old.is_local || old.revision != revision || old.room_key != remote.room_key {
            return Err(Error::Conflict);
        }
        put(c, Record::Message(remote))?;
        execute(
            c,
            "DELETE FROM cache_messages WHERE message_key=?",
            &[local],
        )?;
        recompute_room(c, &old.room_key, true)?;
    } else {
        let existing = select::<Message>(
            c,
            "SELECT * FROM cache_messages WHERE message_key=?",
            &[&remote.message_key],
        )?
        .pop()
        .ok_or(Error::Conflict)?;
        remote.revision = existing.revision;
        if existing != remote {
            return Err(Error::Conflict);
        }
    }
    Ok(())
}

#[allow(clippy::too_many_lines, reason = "Atomic cache command dispatch")]
pub(crate) fn write(
    c: &mut SqliteConnection,
    epoch: &str,
    mutations: Vec<Mutation>,
    receipt: &mut Commit,
) -> Result<Changes, Error> {
    if info(c)?.epoch != epoch {
        return Err(Error::Conflict);
    }
    if mutations.len() != 1 && mutations.iter().any(|m| matches!(m, Mutation::ResetCache)) {
        return Err(invalid("ResetCache must be its own batch"));
    }
    execute(c, "DELETE FROM cache_changes", &[])?;
    let mut pages = BTreeSet::new();
    for m in mutations {
        match m {
            Mutation::Put(r) => put(c, r)?,
            Mutation::UpdateStatus {
                expected_version,
                status,
            } => update_status(c, &expected_version, status)?,
            Mutation::Delete(d) => delete(c, d)?,
            Mutation::ReplaceReferences(r) => references(c, r)?,
            Mutation::ResetTimeline {
                paging_key,
                account_keys,
            } => {
                if !pages.insert(paging_key.clone()) {
                    return Err(invalid("duplicate page command"));
                }
                reset(c, &paging_key, account_keys)?;
            }
            Mutation::CommitPage(p) => {
                if !pages.insert(p.paging_key.clone()) {
                    return Err(invalid("duplicate page command"));
                }
                page(c, p)?;
            }
            Mutation::ReplaceListMembers {
                list_key,
                member_keys,
            } => {
                execute(
                    c,
                    "DELETE FROM cache_list_members WHERE list_key=?",
                    &[&list_key],
                )?;
                for member_key in member_keys.into_iter().collect::<BTreeSet<_>>() {
                    put(
                        c,
                        Record::ListMember(ListMember {
                            list_key: list_key.clone(),
                            member_key,
                        }),
                    )?;
                }
            }
            Mutation::ReplaceRoomMembers {
                room_key,
                user_keys,
            } => {
                execute(
                    c,
                    "DELETE FROM cache_room_members WHERE room_key=?",
                    &[&room_key],
                )?;
                for user_key in user_keys.into_iter().collect::<BTreeSet<_>>() {
                    put(
                        c,
                        Record::RoomMember(RoomMember {
                            room_key: room_key.clone(),
                            user_key,
                        }),
                    )?;
                }
            }
            Mutation::StartTranslation {
                id,
                source_hash,
                attempt_count,
                updated_at,
            } => {
                let changed = diesel::update(
                    translations::table
                        .find(id)
                        .filter(translations::source_hash.eq(source_hash))
                        .filter(translations::attempt_count.eq(attempt_count))
                        .filter(translations::status.eq("Pending")),
                )
                .set((
                    translations::status.eq("Translating"),
                    translations::payload.eq(None::<String>),
                    translations::status_reason.eq(None::<String>),
                    translations::updated_at.eq(updated_at),
                ))
                .execute(c)?;
                if changed == 0 {
                    return Err(Error::Conflict);
                }
            }
            Mutation::ReplaceRoomMessages { room_key, messages } => {
                nonempty(&room_key)?;
                let mut ids = BTreeSet::new();
                if messages
                    .iter()
                    .any(|m| m.room_key != room_key || !ids.insert(&m.message_key))
                {
                    return Err(invalid("room message scope or identity mismatch"));
                }
                execute(
                    c,
                    "DELETE FROM cache_messages WHERE room_key=?",
                    &[&room_key],
                )?;
                for message in messages {
                    put(c, Record::Message(message))?;
                }
                recompute_room(c, &room_key, true)?;
            }
            Mutation::TranslationResult(r) => {
                translation_state(&r.status)?;
                if !["Completed", "Failed", "Skipped"].contains(&r.status.as_str()) {
                    return Err(invalid("translation result must be terminal"));
                }
                if let Some(p) = &r.payload {
                    json(p)?;
                }
                let changed = diesel::update(
                    translations::table
                        .find(r.id)
                        .filter(translations::source_hash.eq(r.source_hash))
                        .filter(translations::attempt_count.eq(r.attempt_count))
                        .filter(translations::status.eq_any(["Pending", "Translating"])),
                )
                .set((
                    translations::status.eq(r.status),
                    translations::payload.eq(r.payload),
                    translations::status_reason.eq(r.reason),
                    translations::updated_at.eq(r.updated_at),
                ))
                .execute(c)?;
                if changed == 0 {
                    return Err(Error::Conflict);
                }
            }
            Mutation::TranslationDisplay {
                id,
                display_mode,
                updated_at,
            } => {
                display(&display_mode)?;
                diesel::update(translations::table.find(id))
                    .set((
                        translations::display_mode.eq(display_mode),
                        translations::updated_at.eq(updated_at),
                    ))
                    .execute(c)?;
            }
            Mutation::FailStaleTranslations {
                before,
                reason,
                updated_at,
            } => {
                diesel::update(
                    translations::table
                        .filter(translations::status.eq_any(["Pending", "Translating"]))
                        .filter(translations::updated_at.lt(before)),
                )
                .set((
                    translations::status.eq("Failed"),
                    translations::payload.eq(None::<String>),
                    translations::status_reason.eq(reason),
                    translations::updated_at.eq(updated_at),
                ))
                .execute(c)?;
            }
            Mutation::DeleteInFlightTranslations => {
                diesel::delete(
                    translations::table
                        .filter(translations::status.eq_any(["Pending", "Translating"])),
                )
                .execute(c)?;
            }
            Mutation::UpdateLocalMessage {
                message_key,
                expected_revision,
                payload,
            } => {
                json(&payload)?;
                let next = expected_revision.checked_add(1).ok_or(Error::Conflict)?;
                let changed = diesel::update(
                    messages::table
                        .find(message_key)
                        .filter(messages::is_local.eq(true))
                        .filter(messages::revision.eq(expected_revision)),
                )
                .set((messages::payload.eq(payload), messages::revision.eq(next)))
                .execute(c)?;
                if changed == 0 {
                    return Err(Error::Conflict);
                }
            }
            Mutation::AcknowledgeMessage {
                local_key,
                expected_revision,
                remote,
            } => ack(c, &local_key, expected_revision, remote)?,
            Mutation::ClearUnread { account, room_key } => {
                execute(
                    c,
                    "UPDATE cache_room_timelines SET unread_count=0 WHERE account=? AND room_key=?",
                    &[&account, &room_key],
                )?;
            }
            Mutation::ClearAccount(a) => clear_account(c, &a)?,
            Mutation::ResetCache => {
                for table in [
                    "cache_presentations",
                    "cache_timelines",
                    "cache_references",
                    "cache_paging_keys",
                    "cache_statuses",
                    "cache_translations",
                    "cache_relations",
                    "cache_user_history",
                    "cache_users",
                    "cache_emoji_history",
                    "cache_emojis",
                    "cache_list_members",
                    "cache_list_pages",
                    "cache_lists",
                    "cache_room_members",
                    "cache_messages",
                    "cache_room_timelines",
                    "cache_rooms",
                ] {
                    c.batch_execute(&format!("DELETE FROM {table}"))?;
                }
                c.batch_execute("UPDATE cache_meta SET epoch=lower(hex(randomblob(16))); INSERT INTO cache_changes VALUES('all','*',0)")?;
            }
        }
    }
    let changes = select::<Change>(c, "SELECT * FROM cache_changes ORDER BY topic,entry", &[])?;
    for change in &changes {
        if change.topic == "timeline" && change.structure != 0 {
            execute(
                c,
                "UPDATE cache_paging_keys SET revision=revision+1 WHERE paging_key=?",
                &[&change.entry],
            )?;
        }
    }
    receipt.epoch = info(c)?.epoch;
    for key in pages {
        if let Some(page) = metadata(c, &key)? {
            receipt.pages.push(page);
        }
    }
    execute(c, "DELETE FROM cache_changes", &[])?;
    Ok(Changes {
        entries: changes.into_iter().map(|r| (r.topic, r.entry)).collect(),
    })
}
