diesel::table! {cache_statuses(id){id->Text,account->Text,status_key->Text,payload->Text,fingerprint->BigInt,render_hash->BigInt,text->Nullable<Text>,version->Text,}}
diesel::table! {cache_references(status_id,reference_type,reference_status_id){status_id->Text,reference_type->Text,reference_status_id->Text,reference_order->Integer,}}
diesel::table! {cache_timelines(paging_key,timeline_id){paging_key->Text,timeline_id->Text,status_id->Text,sort_id->BigInt,is_gap->Bool,message->Nullable<Text>,message_render_hash->Nullable<BigInt>,notification_key->Nullable<Text>,content_revision->BigInt,}}
diesel::table! {cache_presentations(paging_key,timeline_id,presentation_type,reference_status_id){paging_key->Text,timeline_id->Text,presentation_type->Text,reference_status_id->Text,reference_order->Integer,}}
diesel::table! {cache_paging_keys(paging_key){paging_key->Text,generation->Text,revision->BigInt,next_key->Nullable<Text>,prev_key->Nullable<Text>,source_heads->Text,account_keys->Text,}}
diesel::table! {cache_users(user_key){user_key->Text,name->Text,canonical_handle->Text,host->Text,payload->Text,}}
diesel::table! {cache_relations(account,user_key){account->Text,user_key->Text,payload->Text,}}
diesel::table! {cache_user_history(account,user_key){account->Text,user_key->Text,last_visit->BigInt,}}
diesel::table! {cache_emojis(host){host->Text,payload->Text,}}
diesel::table! {cache_emoji_history(account,short_code){account->Text,short_code->Text,last_use->BigInt,}}
diesel::table! {cache_lists(id){id->Text,account->Text,list_key->Text,payload->Text,}}
diesel::table! {cache_list_pages(paging_key,account,list_key){paging_key->Text,account->Text,list_key->Text,position->BigInt,}}
diesel::table! {cache_list_members(list_key,member_key){list_key->Text,member_key->Text,}}
diesel::table! {cache_room_timelines(account,room_key){account->Text,room_key->Text,sort_id->BigInt,unread_count->BigInt,payload->Text,}}
diesel::table! {cache_rooms(room_key){room_key->Text,platform_id->Text,last_message_key->Nullable<Text>,}}
diesel::table! {cache_room_members(room_key,user_key){room_key->Text,user_key->Text,}}
diesel::table! {cache_messages(message_key){message_key->Text,room_key->Text,user_key->Text,timestamp->BigInt,payload->Text,show_sender->Bool,is_local->Bool,remote_cursor->Nullable<Text>,revision->BigInt,}}
diesel::table! {cache_translations(id){id->Text,entity_type->Text,entity_key->Text,target_language->Text,source_hash->Text,status->Text,display_mode->Text,payload->Nullable<Text>,status_reason->Nullable<Text>,attempt_count->BigInt,updated_at->BigInt,}}
pub(crate) use {
    cache_emoji_history as emoji_history, cache_emojis as emojis,
    cache_list_members as list_members, cache_list_pages as list_pages, cache_lists as lists,
    cache_messages as messages, cache_paging_keys as paging_keys,
    cache_presentations as presentations, cache_references as references,
    cache_relations as relations, cache_room_members as room_members,
    cache_room_timelines as room_timelines, cache_rooms as rooms, cache_statuses as statuses,
    cache_timelines as timelines, cache_translations as translations,
    cache_user_history as user_history, cache_users as users,
};
diesel::joinable!(cache_timelines -> cache_statuses(status_id));
diesel::allow_tables_to_appear_in_same_query!(
    cache_statuses,
    cache_references,
    cache_timelines,
    cache_presentations,
    cache_paging_keys,
    cache_users,
    cache_relations,
    cache_user_history,
    cache_emojis,
    cache_emoji_history,
    cache_lists,
    cache_list_pages,
    cache_list_members,
    cache_room_timelines,
    cache_rooms,
    cache_room_members,
    cache_messages,
    cache_translations
);
