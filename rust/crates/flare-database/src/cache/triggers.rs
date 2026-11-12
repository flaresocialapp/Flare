use crate::Error;
use diesel::{connection::SimpleConnection, prelude::*, sql_types::Text};
use std::fmt::Write as _;

fn mark(topic: &str, entry: &str, structure: &str) -> String {
    format!(
        "INSERT INTO cache_changes VALUES('{topic}',{entry},{structure}) ON CONFLICT(topic,entry) DO UPDATE SET structure=max(structure,excluded.structure);"
    )
}
// Start at the changed target, then walk reverse indexes to the owning timeline rows.
fn content_rows(id: &str) -> String {
    format!("SELECT t.rowid FROM cache_timelines t WHERE t.status_id={id}
        UNION SELECT t.rowid FROM cache_references r JOIN cache_timelines t ON t.status_id=r.status_id WHERE r.reference_status_id={id}
        UNION SELECT t.rowid FROM cache_presentations p JOIN cache_timelines t ON t.paging_key=p.paging_key AND t.timeline_id=p.timeline_id WHERE p.reference_status_id={id}
        UNION SELECT t.rowid FROM cache_references r JOIN cache_presentations p ON p.reference_status_id=r.status_id AND p.presentation_type='InlineParent' JOIN cache_timelines t ON t.paging_key=p.paging_key AND t.timeline_id=p.timeline_id WHERE r.reference_status_id={id}")
}
fn reference_rows(id: &str) -> String {
    format!("SELECT t.rowid FROM cache_timelines t WHERE t.status_id={id}
        UNION SELECT t.rowid FROM cache_presentations p JOIN cache_timelines t ON t.paging_key=p.paging_key AND t.timeline_id=p.timeline_id WHERE p.reference_status_id={id} AND p.presentation_type='InlineParent'")
}
fn snapshot(rows: &str, condition: &str) -> String {
    format!(
        "INSERT INTO cache_changes SELECT 'timeline_snapshot',paging_key,0 FROM cache_timelines WHERE rowid IN ({rows}) AND {condition} GROUP BY paging_key ON CONFLICT(topic,entry) DO UPDATE SET structure=0;"
    )
}
fn revise(rows: &str, condition: &str) -> String {
    format!(
        "UPDATE cache_timelines SET content_revision=content_revision+1 WHERE rowid IN ({rows}) AND {condition};"
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
            match table {
                "cache_statuses" => {
                    let rows = content_rows(&format!("{alias}.id"));
                    body += &snapshot(&rows, "1");
                    let render = if event == "UPDATE" {
                        "(OLD.fingerprint IS NOT NEW.fingerprint OR OLD.render_hash IS NOT NEW.render_hash)"
                    } else {
                        "1"
                    };
                    body += &revise(&rows, render);
                }
                "cache_references" => {
                    body += &revise(&reference_rows(&format!("{alias}.status_id")), "1");
                }
                "cache_presentations" => {
                    let _ = write!(
                        body,
                        "UPDATE cache_timelines SET content_revision=content_revision+1 WHERE paging_key={alias}.paging_key AND timeline_id={alias}.timeline_id;"
                    );
                }
                "cache_translations" => {
                    let rows = content_rows(&format!("{alias}.entity_key"));
                    let status = format!("{alias}.entity_type='Status'");
                    body += &snapshot(&rows, &status);
                    let render = if event == "UPDATE" {
                        " AND (OLD.status IS NOT NEW.status OR OLD.display_mode IS NOT NEW.display_mode OR OLD.payload IS NOT NEW.payload OR OLD.status_reason IS NOT NEW.status_reason OR OLD.source_hash IS NOT NEW.source_hash OR OLD.target_language IS NOT NEW.target_language)"
                    } else {
                        ""
                    };
                    body += &revise(&rows, &format!("{status}{render}"));
                }
                _ => {}
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
