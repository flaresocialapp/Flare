diesel::table! {
    #[sql_name = "DbAccount"]
    accounts (account_key) {
        account_key -> Text,
        credential_json -> Text,
        platform_type -> Text,
        last_active -> BigInt,
        sort_id -> BigInt,
    }
}
diesel::table! {
    #[sql_name = "DbDraftGroup"]
    draft_groups (group_id) {
        group_id -> Text,
        content -> Text,
        created_at -> BigInt,
        updated_at -> BigInt,
    }
}
diesel::table! {
    #[sql_name = "DbDraftTarget"]
    draft_targets (target_id) {
        group_id -> Text,
        account_key -> Text,
        status -> Text,
        error_message -> Nullable<Text>,
        attempt_count -> Integer,
        last_attempt_at -> Nullable<BigInt>,
        created_at -> BigInt,
        updated_at -> BigInt,
        target_id -> Text,
    }
}
diesel::table! {
    #[sql_name = "DbDraftMedia"]
    draft_media (media_id) {
        group_id -> Text,
        cache_path -> Text,
        file_name -> Nullable<Text>,
        media_type -> Text,
        alt_text -> Nullable<Text>,
        sort_order -> Integer,
        created_at -> BigInt,
        media_id -> Text,
    }
}
diesel::table! {
    #[sql_name = "DbKeywordFilter"]
    keyword_filters (keyword) {
        keyword -> Text,
        for_timeline -> BigInt,
        for_notification -> BigInt,
        for_search -> BigInt,
        expired_at -> BigInt,
        is_regex -> BigInt,
    }
}
diesel::table! {
    #[sql_name = "DbSearchHistory"]
    search_history (search) {
        search -> Text,
        created_at -> BigInt,
    }
}
diesel::table! {
    #[sql_name = "DbRssSources"]
    rss_sources (id) {
        id -> BigInt,
        url -> Text,
        title -> Nullable<Text>,
        icon -> Nullable<Text>,
        #[sql_name = "displayMode"]
        display_mode -> Text,
        #[sql_name = "lastUpdate"]
        last_update -> BigInt,
        #[sql_name = "type"]
        subscription_type -> Text,
    }
}
diesel::joinable!(draft_targets -> draft_groups (group_id));
diesel::joinable!(draft_media -> draft_groups (group_id));
diesel::allow_tables_to_appear_in_same_query!(draft_groups, draft_targets, draft_media);
