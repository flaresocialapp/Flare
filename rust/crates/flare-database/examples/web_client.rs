//! Browser-test host only. These exports are not linked into Flare's platform bindings.

#[cfg(target_arch = "wasm32")]
mod browser {
    use flare_database::{
        AgentDatabase, AppDatabase, AppMutation, AppQuery, CacheDatabase, Subscription, agent,
        cache,
    };
    use futures::StreamExt;
    use std::{cell::RefCell, collections::HashMap};
    use wasm_bindgen::prelude::*;

    thread_local! {
        static DATABASES: RefCell<HashMap<String, AppDatabase>> = RefCell::default();
        static OBSERVERS: RefCell<HashMap<String, (String, Subscription)>> = RefCell::default();
        static AGENTS: RefCell<HashMap<String, AgentDatabase>> = RefCell::default();
        static AGENT_OBSERVERS: RefCell<HashMap<String, (String, Subscription<agent::Snapshot>)>> = RefCell::default();
        static CACHES: RefCell<HashMap<String, CacheDatabase>> = RefCell::default();
        static CACHE_OBSERVERS: RefCell<HashMap<String, (String, Subscription<cache::Snapshot>)>> = RefCell::default();
    }

    fn error(error: impl std::fmt::Display) -> JsValue {
        JsValue::from_str(&error.to_string())
    }
    fn database(name: &str) -> Result<AppDatabase, JsValue> {
        DATABASES
            .with(|values| values.borrow().get(name).cloned())
            .ok_or_else(|| error("not open"))
    }

    fn agent_database(name: &str) -> Result<AgentDatabase, JsValue> {
        AGENTS
            .with(|values| values.borrow().get(name).cloned())
            .ok_or_else(|| error("Agent not open"))
    }

    fn cache_database(name: &str) -> Result<CacheDatabase, JsValue> {
        CACHES
            .with(|v| v.borrow().get(name).cloned())
            .ok_or_else(|| error("Cache not open"))
    }
    #[wasm_bindgen]
    pub async fn cache_open(name: String, worker_url: String) -> Result<(), JsValue> {
        let db = CacheDatabase::open(&name, &worker_url)
            .await
            .map_err(error)?;
        CACHES.with(|v| v.borrow_mut().insert(name, db));
        Ok(())
    }
    #[wasm_bindgen]
    pub async fn cache_query(name: String, json: String) -> Result<String, JsValue> {
        let value = cache_database(&name)?
            .query(serde_json::from_str(&json).map_err(error)?)
            .await
            .map_err(error)?;
        serde_json::to_string(&value).map_err(error)
    }
    #[wasm_bindgen]
    pub async fn cache_write(name: String, epoch: String, json: String) -> Result<String, JsValue> {
        let value = cache_database(&name)?
            .write(epoch, serde_json::from_str(&json).map_err(error)?)
            .await
            .map_err(error)?;
        serde_json::to_string(&value).map_err(error)
    }
    #[wasm_bindgen]
    pub async fn cache_mark(
        name: String,
        epoch: String,
        id: String,
        field: String,
    ) -> Result<(), JsValue> {
        cache_database(&name)?
            .update_status(&epoch, &id, |row| {
                let mut value: serde_json::Value =
                    serde_json::from_str(&row.payload).map_err(|_| {
                        flare_database::Error::InvalidRequest("invalid test payload".into())
                    })?;
                value
                    .as_object_mut()
                    .ok_or_else(|| {
                        flare_database::Error::InvalidRequest(
                            "test payload is not an object".into(),
                        )
                    })?
                    .insert(field.clone(), true.into());
                row.payload = value.to_string();
                Ok(())
            })
            .await
            .map_err(error)
    }
    #[wasm_bindgen]
    pub async fn cache_subscription_released(name: String) -> Result<(), JsValue> {
        let db = cache_database(&name)?;
        let mut stream = db.observe(cache::Query::Info).await.map_err(error)?;
        let snapshot = stream
            .next()
            .await
            .ok_or_else(|| error("stream closed"))?
            .map_err(error)?;
        let weak = std::sync::Arc::downgrade(&snapshot);
        drop(snapshot);
        drop(stream);
        db.query(cache::Query::Info).await.map_err(error)?;
        if weak.upgrade().is_some() {
            return Err(error("dropped subscription retained its snapshot"));
        }
        Ok(())
    }
    #[wasm_bindgen]
    pub async fn cache_observe(name: String, id: String, json: String) -> Result<(), JsValue> {
        let stream = cache_database(&name)?
            .observe(serde_json::from_str(&json).map_err(error)?)
            .await
            .map_err(error)?;
        CACHE_OBSERVERS.with(|v| v.borrow_mut().insert(id, (name, stream)));
        Ok(())
    }
    #[wasm_bindgen]
    pub async fn cache_next(id: String) -> Result<String, JsValue> {
        let (name, mut stream) = CACHE_OBSERVERS
            .with(|v| v.borrow_mut().remove(&id))
            .ok_or_else(|| error("Cache not observed"))?;
        let value = stream
            .next()
            .await
            .ok_or_else(|| error("Cache stream closed"))?;
        CACHE_OBSERVERS.with(|v| v.borrow_mut().insert(id, (name, stream)));
        serde_json::to_string(value.map_err(error)?.as_ref()).map_err(error)
    }

    #[wasm_bindgen]
    pub async fn agent_open(name: String, worker_url: String) -> Result<(), JsValue> {
        let database = AgentDatabase::open(&name, &worker_url)
            .await
            .map_err(error)?;
        AGENTS.with(|values| values.borrow_mut().insert(name, database));
        Ok(())
    }
    #[wasm_bindgen]
    pub async fn agent_query(name: String, json: String) -> Result<String, JsValue> {
        let value = agent_database(&name)?
            .query(serde_json::from_str(&json).map_err(error)?)
            .await
            .map_err(error)?;
        serde_json::to_string(&value).map_err(error)
    }
    #[wasm_bindgen]
    pub async fn agent_write(name: String, json: String) -> Result<String, JsValue> {
        let value = agent_database(&name)?
            .write(serde_json::from_str(&json).map_err(error)?)
            .await
            .map_err(error)?;
        serde_json::to_string(&value).map_err(error)
    }
    #[wasm_bindgen]
    pub async fn agent_observe(name: String, id: String, json: String) -> Result<(), JsValue> {
        let subscription = agent_database(&name)?
            .observe(serde_json::from_str(&json).map_err(error)?)
            .await
            .map_err(error)?;
        AGENT_OBSERVERS.with(|values| values.borrow_mut().insert(id, (name, subscription)));
        Ok(())
    }
    #[wasm_bindgen]
    pub async fn agent_next(id: String) -> Result<String, JsValue> {
        let (name, mut subscription) = AGENT_OBSERVERS
            .with(|values| values.borrow_mut().remove(&id))
            .ok_or_else(|| error("Agent not observed"))?;
        let value = subscription
            .next()
            .await
            .ok_or_else(|| error("Agent subscription closed"))?;
        AGENT_OBSERVERS.with(|values| values.borrow_mut().insert(id, (name, subscription)));
        serde_json::to_string(value.map_err(error)?.as_ref()).map_err(error)
    }

    #[wasm_bindgen]
    pub async fn open(name: String, worker_url: String) -> Result<(), JsValue> {
        let store = AppDatabase::open(&name, &worker_url).await.map_err(error)?;
        DATABASES.with(|values| values.borrow_mut().insert(name, store));
        Ok(())
    }

    #[wasm_bindgen]
    pub async fn query(name: String, json: String) -> Result<String, JsValue> {
        let query: AppQuery = serde_json::from_str(&json).map_err(error)?;
        let value = database(&name)?.query(query).await.map_err(error)?;
        serde_json::to_string(&value).map_err(error)
    }

    #[wasm_bindgen]
    pub async fn write(name: String, json: String) -> Result<String, JsValue> {
        let mutations: Vec<AppMutation> = serde_json::from_str(&json).map_err(error)?;
        let value = database(&name)?.write(mutations).await.map_err(error)?;
        serde_json::to_string(&value).map_err(error)
    }

    #[wasm_bindgen]
    pub async fn observe(name: String, id: String, json: String) -> Result<(), JsValue> {
        let query: AppQuery = serde_json::from_str(&json).map_err(error)?;
        let subscription = database(&name)?.observe(query).await.map_err(error)?;
        OBSERVERS.with(|values| values.borrow_mut().insert(id, (name, subscription)));
        Ok(())
    }

    #[wasm_bindgen]
    pub async fn next(id: String) -> Result<String, JsValue> {
        let (name, mut subscription) = OBSERVERS
            .with(|values| values.borrow_mut().remove(&id))
            .ok_or_else(|| error("not observing"))?;
        let value = subscription
            .next()
            .await
            .ok_or_else(|| error("subscription closed"))?;
        OBSERVERS.with(|values| values.borrow_mut().insert(id, (name, subscription)));
        let value = value.map_err(error)?;
        serde_json::to_string(&*value).map_err(error)
    }

    #[wasm_bindgen]
    pub fn close(name: &str) {
        OBSERVERS.with(|values| values.borrow_mut().retain(|_, (owner, _)| owner != name));
        DATABASES.with(|values| values.borrow_mut().remove(name));
        AGENTS.with(|values| values.borrow_mut().remove(name));
        AGENT_OBSERVERS.with(|values| values.borrow_mut().retain(|_, (owner, _)| owner != name));
        CACHES.with(|values| values.borrow_mut().remove(name));
        CACHE_OBSERVERS.with(|values| values.borrow_mut().retain(|_, (owner, _)| owner != name));
    }
}
