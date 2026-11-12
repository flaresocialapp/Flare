// Frozen Rust v1/v2 trigger definitions, used only to validate supported upgrade sources.
use crate::Error;
use diesel::{connection::SimpleConnection, prelude::*, sql_types::Text};
use std::fmt::Write as _;

fn mark(topic: &str, entry: &str, structure: &str) -> String {
    format!(
        "INSERT INTO cache_changes VALUES('{topic}',{entry},{structure}) ON CONFLICT(topic,entry) DO UPDATE SET structure=max(structure,excluded.structure);"
    )
}
// Reference targets are soft: a missing quote or InlineParent can arrive in a later page.
fn content(id: &str, condition: &str) -> String {
    format!("UPDATE cache_timelines SET content_revision=content_revision+1 WHERE {condition} AND (status_id={id}
 OR EXISTS(SELECT 1 FROM cache_references r WHERE r.status_id=cache_timelines.status_id AND r.reference_status_id={id})
 OR EXISTS(SELECT 1 FROM cache_presentations p WHERE p.paging_key=cache_timelines.paging_key AND p.timeline_id=cache_timelines.timeline_id AND (p.reference_status_id={id}
 OR (p.presentation_type='InlineParent' AND EXISTS(SELECT 1 FROM cache_references r WHERE r.status_id=p.reference_status_id AND r.reference_status_id={id})))));")
}
fn reference(id: &str) -> String {
    format!(
        "UPDATE cache_timelines SET content_revision=content_revision+1 WHERE status_id={id} OR EXISTS(SELECT 1 FROM cache_presentations p WHERE p.paging_key=cache_timelines.paging_key AND p.timeline_id=cache_timelines.timeline_id AND p.presentation_type='InlineParent' AND p.reference_status_id={id});"
    )
}
#[derive(QueryableByName)]
struct Column {
    #[diesel(sql_type=Text)]
    name: String,
}

pub(super) fn install(c: &mut SqliteConnection) -> Result<(), Error> {
    for (table, topic, column) in [
        ("cache_statuses", "status", "id"),
        ("cache_references", "status", "status_id"),
        ("cache_timelines", "timeline", "paging_key"),
        ("cache_presentations", "timeline", "paging_key"),
        ("cache_paging_keys", "timeline", "paging_key"),
        ("cache_users", "user", ""),
        ("cache_relations", "relation", ""),
        ("cache_user_history", "history", ""),
        ("cache_emojis", "emoji", ""),
        ("cache_emoji_history", "emoji_history", ""),
        ("cache_lists", "list", ""),
        ("cache_list_pages", "list", ""),
        ("cache_list_members", "list", ""),
        ("cache_room_timelines", "room", ""),
        ("cache_rooms", "room", ""),
        ("cache_room_members", "room", ""),
        ("cache_messages", "message", "room_key"),
        ("cache_translations", "translation", ""),
    ] {
        let columns = diesel::sql_query(format!("SELECT name FROM pragma_table_info('{table}')"))
            .load::<Column>(c)?;
        let changed = columns
            .iter()
            .map(|c| format!("OLD.{} IS NOT NEW.{}", c.name, c.name))
            .collect::<Vec<_>>()
            .join(" OR ");
        for (event, alias) in [("INSERT", "NEW"), ("DELETE", "OLD"), ("UPDATE", "NEW")] {
            let entry = if column.is_empty() {
                "'*'".into()
            } else {
                format!("{alias}.{column}")
            };
            let structure = if table == "cache_timelines" {
                if event == "UPDATE" {
                    "(OLD.status_id IS NOT NEW.status_id OR OLD.sort_id IS NOT NEW.sort_id OR OLD.is_gap IS NOT NEW.is_gap OR OLD.message IS NOT NEW.message OR OLD.notification_key IS NOT NEW.notification_key)"
                } else {
                    "1"
                }
            } else {
                "0"
            };
            let mut body = mark(topic, &entry, structure);
            if table == "cache_statuses" {
                let meaningful = if event == "UPDATE" {
                    "(OLD.fingerprint IS NOT NEW.fingerprint OR OLD.render_hash IS NOT NEW.render_hash)"
                } else {
                    "1"
                };
                body += &content(&format!("{alias}.id"), meaningful);
            } else if table == "cache_references" {
                body += &reference(&format!("{alias}.status_id"));
            } else if table == "cache_presentations" {
                let _ = write!(
                    body,
                    "UPDATE cache_timelines SET content_revision=content_revision+1 WHERE paging_key={alias}.paging_key AND timeline_id={alias}.timeline_id;"
                );
            } else if table == "cache_translations" {
                let changed = if event == "UPDATE" {
                    " AND (OLD.status IS NOT NEW.status OR OLD.display_mode IS NOT NEW.display_mode OR OLD.payload IS NOT NEW.payload OR OLD.status_reason IS NOT NEW.status_reason OR OLD.source_hash IS NOT NEW.source_hash OR OLD.target_language IS NOT NEW.target_language)"
                } else {
                    ""
                };
                body += &content(
                    &format!("{alias}.entity_key"),
                    &format!("{alias}.entity_type='Status'{changed}"),
                );
            }
            let condition = if event == "UPDATE" {
                format!("WHEN {changed}")
            } else {
                String::new()
            };
            c.batch_execute(&format!("CREATE TRIGGER {table}_{event} AFTER {event} ON {table} {condition} BEGIN {body} END;"))?;
        }
    }
    Ok(())
}
