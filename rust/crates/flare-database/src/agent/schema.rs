diesel::table! {
    #[sql_name = "agent_conversations"]
    conversations (conversation_id) {
        conversation_id -> Text, title -> Nullable<Text>, title_generated -> Bool,
        created_at -> BigInt, updated_at -> BigInt, is_running -> Bool,
        run_id -> Nullable<Text>, error_message -> Nullable<Text>, retry_json -> Nullable<Text>,
    }
}
diesel::table! {
    #[sql_name = "agent_messages"]
    messages (conversation_id, message_id) {
        conversation_id -> Text, message_id -> Text, position -> BigInt,
        role -> Text, text -> Text, content_json -> Text, message_json -> Text,
        created_at -> BigInt, content_revision -> BigInt,
    }
}
diesel::table! {
    #[sql_name = "agent_contexts"]
    contexts (conversation_id) {
        conversation_id -> Text, summary -> Text, through_message_id -> Text, through_position -> BigInt,
    }
}
diesel::joinable!(messages -> conversations (conversation_id));
diesel::joinable!(contexts -> conversations (conversation_id));
diesel::allow_tables_to_appear_in_same_query!(conversations, messages, contexts);
