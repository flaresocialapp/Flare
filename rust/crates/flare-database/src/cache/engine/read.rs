use super::{Count, TextValue, info, invalid, metadata, select};
use crate::{
    Error,
    cache::{
        Emoji, Identity, IdentityPage, Joined, List, Message, Page, PageVersion,
        PresentationReference, Query, Range, Record, RecordPage, Reference, Relation, Room,
        RoomMember, RoomTimeline, Snapshot, Status, StatusBundle, StatusPage, TimelineCursor,
        TimelineRow, TimelineScan, Translation, User,
    },
};
use diesel::{prelude::*, sqlite::Sqlite};
use std::collections::{BTreeMap, BTreeSet};

fn range(r: &Range) -> Result<String, Error> {
    if r.offset < 0 || !(1..=1000).contains(&r.limit) {
        return Err(invalid(
            "cache range requires nonnegative offset and limit 1..1000",
        ));
    }
    Ok(format!("LIMIT {} OFFSET {}", r.limit, r.offset))
}
fn direction(r: &Range) -> &'static str {
    if r.descending { "DESC" } else { "ASC" }
}
fn like(text: &str) -> String {
    format!(
        "%{}%",
        text.replace('\\', "\\\\")
            .replace('%', "\\%")
            .replace('_', "\\_")
    )
}
fn by_ids<T: QueryableByName<Sqlite> + 'static>(
    c: &mut SqliteConnection,
    table: &str,
    column: &str,
    ids: &[String],
) -> Result<Vec<T>, Error> {
    let ids: BTreeSet<_> = ids.iter().map(String::as_str).collect();
    let ids: Vec<_> = ids.into_iter().collect();
    let mut result = Vec::new();
    for chunk in ids.chunks(400) {
        let marks = vec!["?"; chunk.len()].join(",");
        result.extend(select(
            c,
            &format!("SELECT * FROM {table} WHERE {column} IN ({marks}) ORDER BY {column}"),
            chunk,
        )?);
    }
    Ok(result)
}
fn statuses(
    c: &mut SqliteConnection,
    ids: &[String],
    extra: &[String],
) -> Result<StatusBundle, Error> {
    let mut references = by_ids::<Reference>(c, "cache_references", "status_id", ids)?;
    let mut nested = by_ids::<Reference>(c, "cache_references", "status_id", extra)?;
    references.append(&mut nested);
    references.sort_by(|a, b| {
        (
            &a.status_id,
            &a.reference_type,
            a.reference_order,
            &a.reference_status_id,
        )
            .cmp(&(
                &b.status_id,
                &b.reference_type,
                b.reference_order,
                &b.reference_status_id,
            ))
    });
    references.dedup();
    let mut all = ids.to_vec();
    all.extend(extra.iter().cloned());
    all.extend(references.iter().map(|r| r.reference_status_id.clone()));
    let mut rows = by_ids::<Status>(c, "cache_statuses", "id", &all)?;
    rows.sort_by(|a, b| a.id.cmp(&b.id));
    let mut translations = translations(c, "Status", &all)?;
    translations.sort_by(|a, b| a.id.cmp(&b.id));
    Ok(StatusBundle {
        statuses: rows,
        references,
        translations,
    })
}
fn translations(
    c: &mut SqliteConnection,
    entity_type: &str,
    ids: &[String],
) -> Result<Vec<Translation>, Error> {
    let ids: BTreeSet<_> = ids.iter().map(String::as_str).collect();
    let ids: Vec<_> = ids.into_iter().collect();
    let mut rows = Vec::new();
    for chunk in ids.chunks(400) {
        let mut args = vec![entity_type];
        args.extend_from_slice(chunk);
        rows.extend(select(c,&format!("SELECT * FROM cache_translations WHERE entity_type=? AND entity_key IN ({}) ORDER BY entity_key,id",vec!["?";chunk.len()].join(",")),&args)?);
    }
    Ok(rows)
}
fn page(
    c: &mut SqliteConnection,
    pkey: &str,
    rows: Vec<TimelineRow>,
    has_more: bool,
) -> Result<Page, Error> {
    let ids: BTreeSet<_> = rows.iter().map(|r| r.timeline_id.as_str()).collect();
    let mut presentations = Vec::new();
    let ids: Vec<_> = ids.into_iter().collect();
    for chunk in ids.chunks(400) {
        let mut args = vec![pkey];
        args.extend_from_slice(chunk);
        presentations.extend(select::<PresentationReference>(c,&format!("SELECT * FROM cache_presentations WHERE paging_key=? AND timeline_id IN ({}) ORDER BY timeline_id,presentation_type,reference_order,reference_status_id",vec!["?";chunk.len()].join(",")),&args)?);
    }
    let roots: Vec<_> = rows.iter().map(|r| r.status_id.clone()).collect();
    let parents: Vec<_> = presentations
        .iter()
        .filter(|p| p.presentation_type == "InlineParent")
        .map(|p| p.reference_status_id.clone())
        .collect();
    let mut bundle = statuses(c, &roots, &parents)?;
    let extras: Vec<_> = presentations
        .iter()
        .map(|p| p.reference_status_id.clone())
        .collect();
    let mut all: BTreeMap<_, _> = bundle
        .statuses
        .into_iter()
        .map(|s| (s.id.clone(), s))
        .collect();
    for s in by_ids::<Status>(c, "cache_statuses", "id", &extras)? {
        all.insert(s.id.clone(), s);
    }
    bundle.statuses = all.into_values().collect();
    let mut translated: BTreeMap<_, _> = bundle
        .translations
        .into_iter()
        .map(|t| (t.id.clone(), t))
        .collect();
    for t in translations(c, "Status", &extras)? {
        translated.insert(t.id.clone(), t);
    }
    Ok(Page {
        info: info(c)?,
        metadata: metadata(c, pkey)?,
        rows,
        statuses: bundle.statuses,
        references: bundle.references,
        presentations,
        translations: translated.into_values().collect(),
        has_more,
    })
}
fn account_timelines(
    c: &mut SqliteConnection,
    account: &str,
    after: Option<&TimelineCursor>,
    limit: i64,
) -> Result<Snapshot, Error> {
    super::nonempty(account)?;
    range(&Range {
        offset: 0,
        limit,
        descending: false,
    })?;
    let (status_id, paging_key, timeline_id) = after.map_or(("", "", ""), |cursor| {
        (
            cursor.status_id.as_str(),
            cursor.paging_key.as_str(),
            cursor.timeline_id.as_str(),
        )
    });
    let mut rows = select::<TimelineRow>(
        c,
        &format!(
            "SELECT t.* FROM cache_statuses s CROSS JOIN cache_timelines t ON t.status_id=s.id WHERE s.account=? AND s.id>=? AND (t.status_id,t.paging_key,t.timeline_id)>(?,?,?) ORDER BY s.id,t.paging_key,t.timeline_id LIMIT {}",
            limit + 1
        ),
        &[account, status_id, status_id, paging_key, timeline_id],
    )?;
    let has_more = rows.len() > usize::try_from(limit).unwrap_or(0);
    if has_more {
        rows.pop();
    }
    let next = rows.last().filter(|_| has_more).map(|row| TimelineCursor {
        status_id: row.status_id.clone(),
        paging_key: row.paging_key.clone(),
        timeline_id: row.timeline_id.clone(),
    });
    let mut groups: BTreeMap<String, Vec<TimelineRow>> = BTreeMap::new();
    for row in rows {
        groups.entry(row.paging_key.clone()).or_default().push(row);
    }
    let pages = groups
        .into_iter()
        .map(|(key, rows)| page(c, &key, rows, false))
        .collect::<Result<_, _>>()?;
    Ok(Snapshot::TimelineScan(TimelineScan {
        info: info(c)?,
        pages,
        next,
    }))
}
fn joined_users(
    c: &mut SqliteConnection,
    records: Vec<Record>,
    ids: &[String],
    messages: Vec<Message>,
) -> Result<Snapshot, Error> {
    Ok(Snapshot::Joined(Joined {
        records,
        users: by_ids(c, "cache_users", "user_key", ids)?,
        messages,
    }))
}
fn record_page<T: QueryableByName<Sqlite> + 'static>(
    c: &mut SqliteConnection,
    sql: &str,
    args: &[&str],
    r: &Range,
    record: fn(T) -> Record,
) -> Result<RecordPage, Error> {
    range(r)?;
    let mut rows = select::<T>(
        c,
        &format!("{sql} LIMIT {} OFFSET {}", r.limit + 1, r.offset),
        args,
    )?;
    let has_more = rows.len() > usize::try_from(r.limit).unwrap_or(0);
    if has_more {
        rows.pop();
    }
    Ok(RecordPage {
        records: rows.into_iter().map(record).collect(),
        users: vec![],
        has_more,
    })
}
fn rooms(c: &mut SqliteConnection, timelines: Vec<RoomTimeline>) -> Result<Snapshot, Error> {
    let ids: Vec<_> = timelines.iter().map(|r| r.room_key.clone()).collect();
    let members = by_ids::<RoomMember>(c, "cache_room_members", "room_key", &ids)?;
    let room_rows = by_ids::<Room>(c, "cache_rooms", "room_key", &ids)?;
    let message_ids: Vec<_> = room_rows
        .iter()
        .filter_map(|r| r.last_message_key.clone())
        .collect();
    let messages = by_ids::<Message>(c, "cache_messages", "message_key", &message_ids)?;
    let mut users: Vec<_> = members.iter().map(|m| m.user_key.clone()).collect();
    users.extend(messages.iter().map(|m| m.user_key.clone()));
    let mut records: Vec<_> = timelines.into_iter().map(Record::RoomTimeline).collect();
    records.extend(room_rows.into_iter().map(Record::Room));
    records.extend(members.into_iter().map(Record::RoomMember));
    joined_users(c, records, &users, messages)
}

#[allow(
    clippy::too_many_lines,
    reason = "Closed dispatch over cache query shapes"
)]
pub(crate) fn read(c: &mut SqliteConnection, q: &Query) -> Result<Snapshot, Error> {
    macro_rules! rows {
        ($ty:ident,$sql:expr,$args:expr) => {
            Snapshot::Records(
                select::<$ty>(c, $sql, $args)?
                    .into_iter()
                    .map(Record::$ty)
                    .collect(),
            )
        };
    }
    Ok(match q {
  Query::Info=>Snapshot::Info(info(c)?),
  Query::PagingKey(key)=>Snapshot::Records(metadata(c,key)?.into_iter().map(Record::PagingKey).collect()),
  Query::TimelinePage{paging_key,range:r}|Query::TimelineIdentities{paging_key,range:r}=>{
   range(r)?;let d=direction(r);let tail=format!("FROM cache_timelines WHERE paging_key=? ORDER BY sort_id {d},timeline_id {d} LIMIT {} OFFSET {}",r.limit+1,r.offset);
   if matches!(q,Query::TimelineIdentities{..}) {
    let mut rows=select::<Identity>(c,&format!("SELECT timeline_id,status_id,sort_id,content_revision,message_render_hash {tail}"),&[paging_key])?;
    let has_more=rows.len()>usize::try_from(r.limit).unwrap_or(0);if has_more{rows.pop();}
    Snapshot::Identities(IdentityPage{info:info(c)?,metadata:select::<PageVersion>(c,"SELECT paging_key,generation,revision FROM cache_paging_keys WHERE paging_key=?",&[paging_key])?.pop(),rows,has_more})
   }else{
    let mut rows=select::<TimelineRow>(c,&format!("SELECT * {tail}"),&[paging_key])?;let has_more=rows.len()>usize::try_from(r.limit).unwrap_or(0);if has_more{rows.pop();}Snapshot::Page(page(c,paging_key,rows,has_more)?)
   }
  }
  Query::TimelineItems{paging_key,timeline_ids}=>{
   let mut rows=Vec::new();for chunk in timeline_ids.chunks(400){let mut args=vec![paging_key.as_str()];args.extend(chunk.iter().map(String::as_str));rows.extend(select::<TimelineRow>(c,&format!("SELECT * FROM cache_timelines WHERE paging_key=? AND timeline_id IN ({})",vec!["?";chunk.len()].join(",")),&args)?);}
   rows.sort_by(|a,b|(a.sort_id,&a.timeline_id).cmp(&(b.sort_id,&b.timeline_id)));rows.dedup_by(|a,b|a.timeline_id==b.timeline_id);Snapshot::Page(page(c,paging_key,rows,false)?)
  }
  Query::AccountTimelines{account,after,limit}=>account_timelines(c,account,after.as_ref(),*limit)?,
  Query::SizeThrough{paging_key,timeline_id}=>Snapshot::Count(select::<Count>(c,"SELECT COUNT(*) AS value FROM cache_timelines t JOIN cache_timelines anchor ON anchor.paging_key=t.paging_key WHERE t.paging_key=? AND anchor.timeline_id=? AND (t.sort_id,t.timeline_id)<=(anchor.sort_id,anchor.timeline_id)",&[paging_key,timeline_id])?.remove(0).value),
  Query::NextRow{paging_key,timeline_id}=>Snapshot::Records(select::<TimelineRow>(c,"SELECT t.* FROM cache_timelines t JOIN cache_timelines anchor ON anchor.paging_key=t.paging_key WHERE t.paging_key=? AND anchor.timeline_id=? AND (t.sort_id,t.timeline_id)>(anchor.sort_id,anchor.timeline_id) ORDER BY t.sort_id,t.timeline_id LIMIT 1",&[paging_key,timeline_id])?.into_iter().map(Record::Timeline).collect()),
  Query::Statuses(ids)=>Snapshot::StatusBundle(statuses(c,ids,&[])?),
  Query::Status(id)=>Snapshot::Status(select::<Status>(c,"SELECT * FROM cache_statuses WHERE id=?",&[id])?.pop()),
  Query::References(ids)=>Snapshot::Records(by_ids::<Reference>(c,"cache_references","status_id",ids)?.into_iter().map(Record::Reference).collect()),
  Query::SearchStatuses{text,range:r}=>{
    range(r)?;let mut roots=keys(c,&format!("SELECT id AS value FROM cache_statuses WHERE text LIKE ? ESCAPE '\\' ORDER BY id {} LIMIT {} OFFSET {}",direction(r),r.limit+1,r.offset),&[&like(text)])?;
    let has_more=roots.len()>usize::try_from(r.limit).unwrap_or(0);if has_more{roots.pop();}
    Snapshot::StatusPage(StatusPage{bundle:statuses(c,&roots,&[])?,root_ids:roots,has_more})
  }
  Query::Users(ids)=>Snapshot::Records(by_ids::<User>(c,"cache_users","user_key",ids)?.into_iter().map(Record::User).collect()),
  Query::UserByHandle{canonical_handle,host}=>rows!(User,"SELECT * FROM cache_users WHERE canonical_handle=? AND host=? ORDER BY user_key",&[canonical_handle,host]),
  Query::SearchUsers{text,range:r}=>Snapshot::RecordsPage(record_page(c,&format!("SELECT * FROM cache_users WHERE name LIKE ? ESCAPE '\\' OR canonical_handle LIKE ? ESCAPE '\\' ORDER BY name COLLATE NOCASE {},user_key {}",direction(r),direction(r)),&[&like(text),&like(text)],r,Record::User)?),
  Query::UserHistory{account,range:r}=>{
   let d=direction(r);let sql=format!("SELECT * FROM cache_user_history {} ORDER BY last_visit {d},user_key {d},account {d}",if account.is_some(){"WHERE account=?"}else{""});
   let args:Vec<_>=account.iter().map(String::as_str).collect();let mut page=record_page(c,&sql,&args,r,Record::UserHistory)?;
   let ids:Vec<_>=page.records.iter().filter_map(|r|if let Record::UserHistory(h)=r{Some(h.user_key.clone())}else{None}).collect();
   page.users=by_ids(c,"cache_users","user_key",&ids)?;Snapshot::RecordsPage(page)
  }
  Query::Relation{account,user_key}=>rows!(Relation,"SELECT * FROM cache_relations WHERE account=? AND user_key=?",&[account,user_key]),
  Query::Emoji(host)=>rows!(Emoji,"SELECT * FROM cache_emojis WHERE host=?",&[host]),
  Query::EmojiHistory{account,range:r}=>Snapshot::RecordsPage(record_page(c,&format!("SELECT * FROM cache_emoji_history WHERE account=? ORDER BY last_use {},short_code {}",direction(r),direction(r)),&[account],r,Record::EmojiHistory)?),
  Query::List{account,list_key}=>rows!(List,"SELECT * FROM cache_lists WHERE account=? AND list_key=?",&[account,list_key]),
  Query::Lists{paging_key,range:r}=>{let d=direction(r);rows!(List,&format!("SELECT l.* FROM cache_list_pages p JOIN cache_lists l ON l.account=p.account AND l.list_key=p.list_key WHERE p.paging_key=? ORDER BY p.position {d},l.id {d} {}",range(r)?),&[paging_key])}
  Query::ListMembers{list_key,range:r}=>{
    let mut page=record_page(c,&format!("SELECT m.* FROM cache_list_members m INNER JOIN cache_users u ON u.user_key=m.member_key WHERE m.list_key=? ORDER BY m.member_key {}",direction(r)),&[list_key],r,Record::ListMember)?;
    let ids:Vec<_>=page.records.iter().filter_map(|r|if let Record::ListMember(m)=r{Some(m.member_key.clone())}else{None}).collect();
    page.users=by_ids(c,"cache_users","user_key",&ids)?;Snapshot::RecordsPage(page)
  }
  Query::ListMemberKeys{list_key,range:r}=>Snapshot::RecordsPage(record_page(c,&format!("SELECT * FROM cache_list_members WHERE list_key=? ORDER BY member_key {}",direction(r)),&[list_key],r,Record::ListMember)?),
  Query::ListsForUser{account,user_key}=>rows!(List,"SELECT l.* FROM cache_lists l JOIN cache_list_members m ON m.list_key=l.list_key WHERE l.account=? AND m.member_key=? ORDER BY l.id",&[account,user_key]),
  Query::Rooms{account,range:r}=>{let d=direction(r);let rows=select(c,&format!("SELECT * FROM cache_room_timelines WHERE account=? ORDER BY sort_id {d},room_key {d} {}",range(r)?),&[account])?;rooms(c,rows)?}
  Query::Room{account,room_key}=>{let rows=select(c,"SELECT * FROM cache_room_timelines WHERE account=? AND room_key=?",&[account,room_key])?;rooms(c,rows)?}
  Query::RoomMessages{room_key,range:r}=>{let d=direction(r);let rows=select::<Message>(c,&format!("SELECT * FROM cache_messages WHERE room_key=? ORDER BY timestamp {d},message_key {d} {}",range(r)?),&[room_key])?;let ids:Vec<_>=rows.iter().map(|r|r.user_key.clone()).collect();joined_users(c,vec![],&ids,rows)?}
  Query::LatestRemoteMessage(room)=>rows!(Message,"SELECT * FROM cache_messages WHERE room_key=? AND is_local=0 ORDER BY timestamp DESC,message_key DESC LIMIT 1",&[room]),
  Query::Message(id)=>Snapshot::Message(select::<Message>(c,"SELECT * FROM cache_messages WHERE message_key=?",&[id])?.pop()),
  Query::Translations{entity_type,entity_keys,language}=>{let mut rows=translations(c,entity_type,entity_keys)?;rows.retain(|r|language.as_ref().is_none_or(|l|r.target_language==*l));rows.sort_by(|a,b|a.id.cmp(&b.id));Snapshot::Records(rows.into_iter().map(Record::Translation).collect())}
  Query::Stats=>{let mut stats=Vec::new();for table in super::super::migration::TABLES{let count=select::<Count>(c,&format!("SELECT COUNT(*) AS value FROM {table}"),&[])?.remove(0).value;stats.push((table.into(),count));}Snapshot::Stats(stats)}
 })
}

pub(super) fn keys(
    c: &mut SqliteConnection,
    sql: &str,
    args: &[&str],
) -> Result<Vec<String>, Error> {
    Ok(select::<TextValue>(c, sql, args)?
        .into_iter()
        .map(|r| r.value)
        .collect())
}
