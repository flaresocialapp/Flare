use crate::{
    Error, Observation, SnapshotSender,
    engine::State,
    publish,
    storage::{Batch, Kind, Query, Receipt, Value, wrong_kind},
};
use futures_util::{
    future::{Either, select},
    pin_mut,
};
use serde::{Deserialize, Serialize};
use std::{
    cell::RefCell,
    collections::{BTreeMap, HashMap},
    rc::Rc,
    sync::{Arc, Weak},
};
use tokio::sync::{mpsc, oneshot, watch};
use wasm_bindgen::{JsCast, prelude::*};
use web_sys::{BroadcastChannel, Event, MessageEvent, Url, Worker, WorkerOptions, WorkerType};

// Bump when the request/response contract changes, including new query variants.
const PROTOCOL: u32 = 6;

#[derive(Serialize, Deserialize)]
struct Envelope {
    protocol: u32,
    #[serde(default)]
    database: Kind,
    message: Message,
}
#[derive(Serialize, Deserialize)]
enum Message {
    Hello {
        client: String,
    },
    Disconnect {
        client: String,
    },
    Ready {
        client: Option<String>,
        epoch: String,
        schema: i32,
    },
    Request {
        client: String,
        epoch: String,
        id: String,
        command: Command,
    },
    Reply {
        client: String,
        epoch: String,
        id: String,
        result: Result<Reply, Error>,
    },
    Snapshot {
        client: String,
        epoch: String,
        id: String,
        result: Result<Value, Error>,
    },
}
#[derive(Serialize, Deserialize)]
enum Command {
    Query(Query),
    Write(Batch),
    Observe { id: String, query: Query },
    Unsubscribe(String),
}
#[derive(Serialize, Deserialize)]
enum Reply {
    Snapshot(Value),
    Commit(Receipt),
    Done,
}

#[expect(
    clippy::needless_pass_by_value,
    reason = "Result::map_err transfers ownership of JS errors"
)]
fn browser(error: JsValue) -> Error {
    Error::Browser(
        error
            .as_string()
            .unwrap_or_else(|| "browser operation failed".into()),
    )
}

fn post(channel: &BroadcastChannel, kind: Kind, message: Message) -> Result<(), Error> {
    // Keep the payload opaque to JS so i64/u64 never pass through a JS Number.
    let json = serde_json::to_string(&Envelope {
        protocol: PROTOCOL,
        database: kind,
        message,
    })
    .map_err(|_| Error::Browser("request serialization failed".into()))?;
    channel
        .post_message(&JsValue::from_str(&json))
        .map_err(browser)
}

fn decode(event: &MessageEvent) -> Option<Envelope> {
    serde_json::from_str(&event.data().as_string()?).ok()
}

fn uuid() -> Result<String, Error> {
    let crypto =
        js_sys::Reflect::get(&js_sys::global(), &JsValue::from_str("crypto")).map_err(browser)?;
    let method = js_sys::Reflect::get(&crypto, &JsValue::from_str("randomUUID"))
        .map_err(browser)?
        .dyn_into::<js_sys::Function>()
        .map_err(browser)?;
    method
        .call0(&crypto)
        .map_err(browser)?
        .as_string()
        .ok_or_else(|| Error::Browser("randomUUID unavailable".into()))
}

fn valid_name(name: &str) -> Result<(), Error> {
    if name.is_empty()
        || name.len() > 100
        || !name
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
    {
        return Err(Error::InvalidRequest(
            "browser store name must use ASCII letters, digits, '-' or '_'".into(),
        ));
    }
    Ok(())
}

fn channel_name(name: &str) -> String {
    format!("flare-rust-app:{name}")
}

enum Request {
    Query(Query, oneshot::Sender<Result<Value, Error>>),
    Write(Batch, oneshot::Sender<Result<Receipt, Error>>),
    Observe(String, Query, SnapshotSender),
}
enum EventMessage {
    Wire(Envelope),
    Failed(String),
    Unsubscribe(String),
    Resume,
}
enum Pending {
    Query(oneshot::Sender<Result<Value, Error>>),
    Write(oneshot::Sender<Result<Receipt, Error>>),
    Observe,
}
impl Pending {
    fn fail(self, error: Error) {
        match self {
            Self::Query(sender) => {
                let _ = sender.send(Err(error));
            }
            Self::Write(sender) => {
                let _ = sender.send(Err(error));
            }
            Self::Observe => {}
        }
    }
}
struct Observer {
    query: Query,
    sender: SnapshotSender,
}
struct Inner {
    kind: Kind,
    queue: mpsc::Sender<Request>,
    events: mpsc::UnboundedSender<EventMessage>,
    ready: watch::Receiver<Option<Result<(), Error>>>,
}

thread_local! {
    static CLIENTS: RefCell<HashMap<String, Weak<Inner>>> = RefCell::new(HashMap::new());
}

#[derive(Clone)]
pub(crate) struct Backend {
    inner: Arc<Inner>,
}

impl Backend {
    pub(crate) async fn open(name: &str, worker_url: &str, kind: Kind) -> Result<Self, Error> {
        valid_name(name)?;
        let inner = CLIENTS.with(|clients| {
            let mut clients = clients.borrow_mut();
            clients.retain(|_, client| client.strong_count() > 0);
            if let Some(client) = clients
                .get(name)
                .and_then(Weak::upgrade)
                .filter(|c| !c.queue.is_closed())
            {
                if client.kind != kind {
                    return Err(wrong_kind());
                }
                return Ok(client);
            }
            let (queue, receiver) = mpsc::channel(64);
            let (ready, readiness) = watch::channel(None);
            let hub = Hub::connect(name, worker_url, kind, ready)?;
            let inner = Arc::new(Inner {
                kind,
                queue,
                events: hub.events_sender.clone(),
                ready: readiness,
            });
            wasm_bindgen_futures::spawn_local(hub.run(receiver));
            clients.insert(name.into(), Arc::downgrade(&inner));
            Ok::<_, Error>(inner)
        })?;
        let mut ready = inner.ready.clone();
        loop {
            let current = ready.borrow_and_update().clone();
            if let Some(result) = current {
                result?;
                return Ok(Self { inner });
            }
            ready.changed().await.map_err(|_| Error::WorkerStopped)?;
        }
    }

    pub(crate) async fn query(&self, query: Query) -> Result<Value, Error> {
        let (sender, receiver) = oneshot::channel();
        self.inner
            .queue
            .send(Request::Query(query, sender))
            .await
            .map_err(|_| Error::WorkerStopped)?;
        receiver.await.map_err(|_| Error::WorkerStopped)?
    }

    pub(crate) async fn write(&self, mutations: Batch) -> Result<Receipt, Error> {
        let (sender, receiver) = oneshot::channel();
        self.inner
            .queue
            .send(Request::Write(mutations, sender))
            .await
            .map_err(|_| Error::WorkerStopped)?;
        receiver.await.map_err(|_| Error::WorkerStopped)?
    }

    pub(crate) async fn observe(&self, query: Query) -> Result<Observation, Error> {
        let id = uuid()?;
        let (sender, receiver) = watch::channel(None);
        let observation = Observation::new(receiver, self.clone(), id.clone());
        self.inner
            .queue
            .send(Request::Observe(id, query, sender))
            .await
            .map_err(|_| Error::WorkerStopped)?;
        Ok(observation)
    }

    pub(crate) fn unsubscribe(&self, id: &str) {
        let _ = self.inner.events.send(EventMessage::Unsubscribe(id.into()));
    }
}

struct Hub {
    kind: Kind,
    channel: BroadcastChannel,
    worker: Worker,
    events: mpsc::UnboundedReceiver<EventMessage>,
    events_sender: mpsc::UnboundedSender<EventMessage>,
    _listener: Closure<dyn FnMut(MessageEvent)>,
    _error: Closure<dyn FnMut(Event)>,
    lifecycle: Option<PageLifecycle>,
    client: String,
    epoch: Option<String>,
    next_id: u64,
    ready: watch::Sender<Option<Result<(), Error>>>,
    pending: BTreeMap<String, Pending>,
    observations: BTreeMap<String, Observer>,
    failure: Option<Error>,
}

struct PageLifecycle {
    window: web_sys::Window,
    listener: Closure<dyn FnMut(Event)>,
}
impl PageLifecycle {
    fn new(
        window: web_sys::Window,
        channel: BroadcastChannel,
        kind: Kind,
        client: String,
        events: mpsc::UnboundedSender<EventMessage>,
    ) -> Result<Self, Error> {
        let listener = Closure::wrap(Box::new(move |event: Event| {
            if event.type_() == "pagehide" {
                let _ = post(
                    &channel,
                    kind,
                    Message::Disconnect {
                        client: client.clone(),
                    },
                );
            } else {
                let _ = events.send(EventMessage::Resume);
            }
        }) as Box<dyn FnMut(Event)>);
        let lifecycle = Self { window, listener };
        for event in ["pagehide", "pageshow"] {
            lifecycle
                .window
                .add_event_listener_with_callback(
                    event,
                    lifecycle.listener.as_ref().unchecked_ref(),
                )
                .map_err(browser)?;
        }
        Ok(lifecycle)
    }
}
impl Drop for PageLifecycle {
    fn drop(&mut self) {
        for event in ["pagehide", "pageshow"] {
            let _ = self
                .window
                .remove_event_listener_with_callback(event, self.listener.as_ref().unchecked_ref());
        }
    }
}

impl Hub {
    fn connect(
        name: &str,
        worker_url: &str,
        kind: Kind,
        ready: watch::Sender<Option<Result<(), Error>>>,
    ) -> Result<Self, Error> {
        let client = uuid()?;
        let location =
            js_sys::Reflect::get(&js_sys::global(), &"location".into()).map_err(browser)?;
        let href = js_sys::Reflect::get(&location, &"href".into())
            .map_err(browser)?
            .as_string()
            .ok_or_else(|| Error::Browser("worker base URL unavailable".into()))?;
        let url = Url::new_with_base(worker_url, &href).map_err(browser)?;
        url.search_params().set("name", name);
        url.search_params().set("kind", kind.name());
        let channel = BroadcastChannel::new(&channel_name(name)).map_err(browser)?;
        let (sender, events) = mpsc::unbounded_channel();
        let events_sender = sender.clone();
        let wire_sender = sender.clone();
        let listener = Closure::wrap(Box::new(move |event: MessageEvent| {
            if let Some(message) = decode(&event) {
                let _ = wire_sender.send(EventMessage::Wire(message));
            }
        }) as Box<dyn FnMut(MessageEvent)>);
        channel.set_onmessage(Some(listener.as_ref().unchecked_ref()));
        let options = WorkerOptions::new();
        options.set_type(WorkerType::Module);
        let worker = Worker::new_with_options(&url.href(), &options).map_err(|error| {
            channel.set_onmessage(None);
            channel.close();
            browser(error)
        })?;
        let error = Closure::wrap(Box::new(move |event: Event| {
            event.prevent_default();
            let message = event
                .dyn_ref::<web_sys::ErrorEvent>()
                .map(web_sys::ErrorEvent::message)
                .filter(|message| !message.is_empty())
                .unwrap_or_else(|| "database worker failed to load or initialize".into());
            let _ = sender.send(EventMessage::Failed(message));
        }) as Box<dyn FnMut(Event)>);
        worker.set_onerror(Some(error.as_ref().unchecked_ref()));
        let mut hub = Self {
            kind,
            channel,
            worker,
            events,
            events_sender,
            _listener: listener,
            _error: error,
            lifecycle: None,
            client,
            epoch: None,
            next_id: 0,
            ready,
            pending: BTreeMap::new(),
            observations: BTreeMap::new(),
            failure: None,
        };
        if let Some(window) = web_sys::window() {
            hub.lifecycle = Some(PageLifecycle::new(
                window,
                hub.channel.clone(),
                kind,
                hub.client.clone(),
                hub.events_sender.clone(),
            )?);
        }
        post(
            &hub.channel,
            kind,
            Message::Hello {
                client: hub.client.clone(),
            },
        )?;
        Ok(hub)
    }

    fn id(&mut self) -> String {
        self.next_id = self.next_id.wrapping_add(1);
        format!("{}:{}", self.client, self.next_id)
    }

    fn send(&mut self, command: Command, pending: Pending) {
        let Some(epoch) = self.epoch.clone() else {
            pending.fail(Error::OwnerChanged);
            return;
        };
        let id = self.id();
        let result = post(
            &self.channel,
            self.kind,
            Message::Request {
                client: self.client.clone(),
                epoch,
                id: id.clone(),
                command,
            },
        );
        match result {
            Ok(()) => {
                self.pending.insert(id, pending);
            }
            Err(error) => {
                pending.fail(error.clone());
                self.stop(error);
            }
        }
    }

    fn request(&mut self, request: Request) {
        match request {
            Request::Query(query, sender) => {
                if !sender.is_closed() {
                    self.send(Command::Query(query), Pending::Query(sender));
                }
            }
            Request::Write(mutations, sender) => {
                self.send(Command::Write(mutations), Pending::Write(sender));
            }
            Request::Observe(id, query, sender) => {
                if sender.is_closed() {
                    return;
                }
                if self.epoch.is_none() {
                    publish(&sender, Err(Error::OwnerChanged));
                }
                self.observations.insert(
                    id.clone(),
                    Observer {
                        query: query.clone(),
                        sender,
                    },
                );
                self.send(Command::Observe { id, query }, Pending::Observe);
            }
        }
    }

    fn fail_pending(&mut self, error: &Error) {
        for (_, pending) in std::mem::take(&mut self.pending) {
            pending.fail(error.clone());
        }
        for observation in self.observations.values() {
            publish(&observation.sender, Err(error.clone()));
        }
    }

    fn stop(&mut self, error: Error) {
        self.ready.send_replace(Some(Err(error.clone())));
        self.fail_pending(&error);
        self.failure = Some(error);
    }

    #[allow(
        clippy::too_many_lines,
        reason = "Closed dispatch for local lifecycle and database wire events"
    )]
    fn event(&mut self, event: EventMessage) {
        let envelope = match event {
            EventMessage::Wire(envelope) => envelope,
            EventMessage::Failed(message) => {
                self.stop(Error::Browser(message));
                return;
            }
            EventMessage::Unsubscribe(id) => {
                if self.observations.remove(&id).is_some() {
                    self.send(Command::Unsubscribe(id), Pending::Observe);
                }
                return;
            }
            EventMessage::Resume => {
                self.fail_pending(&Error::OwnerChanged);
                self.epoch = None;
                if let Err(error) = post(
                    &self.channel,
                    self.kind,
                    Message::Hello {
                        client: self.client.clone(),
                    },
                ) {
                    self.stop(error);
                }
                return;
            }
        };
        if envelope.protocol != PROTOCOL {
            if matches!(&envelope.message, Message::Ready {client, ..} if client.as_ref().is_none_or(|id| id == &self.client))
            {
                self.stop(Error::Browser(
                    "database protocol changed; reload this page".into(),
                ));
            }
            return;
        }
        if envelope.database != self.kind {
            if matches!(&envelope.message, Message::Ready {client, ..} if client.as_ref().is_none_or(|id| id == &self.client))
            {
                self.stop(wrong_kind());
            }
            return;
        }
        match envelope.message {
            Message::Ready {
                client,
                epoch,
                schema,
            } if client.as_ref().is_none_or(|id| id == &self.client) => {
                if schema != self.kind.version() {
                    let error = Error::Browser("database schema changed; reload this page".into());
                    self.stop(error);
                    return;
                }
                let changed = self.epoch.as_ref() != Some(&epoch);
                if changed {
                    self.fail_pending(&Error::OwnerChanged);
                }
                self.epoch = Some(epoch);
                self.ready.send_replace(Some(Ok(())));
                if changed {
                    let queries: Vec<_> = self
                        .observations
                        .iter()
                        .map(|(id, o)| (id.clone(), o.query.clone()))
                        .collect();
                    for (id, query) in queries {
                        self.send(Command::Observe { id, query }, Pending::Observe);
                    }
                }
            }
            Message::Reply {
                client,
                epoch,
                id,
                result,
            } if client == self.client && self.epoch.as_ref() == Some(&epoch) => {
                if let Some(pending) = self.pending.remove(&id) {
                    match (pending, result) {
                        (Pending::Query(sender), Ok(Reply::Snapshot(value))) => {
                            let _ = sender.send(Ok(value));
                        }
                        (Pending::Write(sender), Ok(Reply::Commit(value))) => {
                            let _ = sender.send(Ok(value));
                        }
                        (pending, Err(error)) => pending.fail(error),
                        (Pending::Observe, Ok(Reply::Done)) => {}
                        (pending, _) => {
                            pending.fail(Error::Browser("invalid database response".into()));
                        }
                    }
                }
            }
            Message::Snapshot {
                client,
                epoch,
                id,
                result,
            } if client == self.client && self.epoch.as_ref() == Some(&epoch) => {
                if let Some(observation) = self.observations.get(&id) {
                    publish(&observation.sender, result);
                }
            }
            _ => {}
        }
    }

    fn prune(&mut self) {
        let closed: Vec<_> = self
            .observations
            .iter()
            .filter(|(_, o)| o.sender.is_closed())
            .map(|(id, _)| id.clone())
            .collect();
        for id in closed {
            self.observations.remove(&id);
            self.send(Command::Unsubscribe(id), Pending::Observe);
        }
    }

    async fn run(mut self, mut requests: mpsc::Receiver<Request>) {
        let mut closed = false;
        loop {
            if self.failure.is_some() {
                break;
            }
            self.prune();
            if closed && self.pending.is_empty() {
                break;
            }
            if closed || self.pending.len() >= 64 {
                let Some(event) = self.events.recv().await else {
                    break;
                };
                self.event(event);
            } else {
                let incoming = {
                    let request = requests.recv();
                    let event = self.events.recv();
                    pin_mut!(request, event);
                    match select(request, event).await {
                        Either::Left((request, _)) => Either::Left(request),
                        Either::Right((event, _)) => Either::Right(event),
                    }
                };
                match incoming {
                    Either::Left(Some(request)) => self.request(request),
                    Either::Left(None) => closed = true,
                    Either::Right(Some(event)) => self.event(event),
                    Either::Right(None) => break,
                }
            }
        }
        let error = self.failure.clone().unwrap_or(Error::WorkerStopped);
        self.fail_pending(&error);
    }
}

impl Drop for Hub {
    fn drop(&mut self) {
        let _ = post(
            &self.channel,
            self.kind,
            Message::Disconnect {
                client: self.client.clone(),
            },
        );
        self.channel.set_onmessage(None);
        self.channel.close();
        self.worker.set_onerror(None);
        self.worker.terminate();
    }
}

/// Internal worker bootstrap, never a platform business/database interface.
#[doc(hidden)]
#[wasm_bindgen(js_name = __flare_database_owner)]
pub async fn start_owner(name: String, kind: String) -> Result<(), JsValue> {
    valid_name(&name).map_err(|e| JsValue::from_str(&e.to_string()))?;
    let kind = match kind.as_str() {
        "app" => Kind::App,
        "agent" => Kind::Agent,
        "cache" => Kind::Cache,
        _ => return Err(JsValue::from_str("invalid database kind")),
    };
    let options = sqlite_wasm_vfs::sahpool::OpfsSAHPoolCfgBuilder::new()
        .directory(&format!("flare-rust-database/{name}"))
        .build();
    let pool = sqlite_wasm_vfs::sahpool::install::<sqlite_wasm_rs::WasmOsCallback>(&options, false)
        .await
        .map_err(|e| JsValue::from_str(&e.to_string()))?;
    let state = State::open(
        &format!("file:{name}.db?vfs=opfs-sahpool"),
        kind,
        |_, version| Err(Error::UnsupportedVersion(version)),
    )
    .map_err(|e| JsValue::from_str(&e.to_string()))?;
    let state = Rc::new(RefCell::new(state));
    let channel = BroadcastChannel::new(&channel_name(&name))?;
    let epoch = uuid().map_err(|e| JsValue::from_str(&e.to_string()))?;
    let sender = channel.clone();
    let owner_epoch = epoch.clone();
    let listener = Closure::wrap(Box::new(move |event: MessageEvent| {
        let Some(envelope) = decode(&event) else {
            return;
        };
        let ready = |client| {
            post(
                &sender,
                kind,
                Message::Ready {
                    client,
                    epoch: owner_epoch.clone(),
                    schema: kind.version(),
                },
            )
        };
        match envelope.message {
            Message::Hello { client } => {
                let _ = ready(Some(client));
            }
            Message::Disconnect { client }
                if envelope.protocol == PROTOCOL && envelope.database == kind =>
            {
                state.borrow_mut().unsubscribe_client(&client);
            }
            Message::Request {
                client,
                epoch,
                id,
                command,
            } if envelope.protocol == PROTOCOL
                && envelope.database == kind
                && epoch == owner_epoch =>
            {
                let result = owner_command(
                    &mut state.borrow_mut(),
                    &sender,
                    kind,
                    &owner_epoch,
                    &client,
                    command,
                );
                let _ = post(
                    &sender,
                    kind,
                    Message::Reply {
                        client,
                        epoch: owner_epoch.clone(),
                        id,
                        result,
                    },
                );
            }
            _ => {}
        }
    }) as Box<dyn FnMut(MessageEvent)>);
    channel.set_onmessage(Some(listener.as_ref().unchecked_ref()));
    post(
        &channel,
        kind,
        Message::Ready {
            client: None,
            epoch,
            schema: kind.version(),
        },
    )
    .map_err(|e| JsValue::from_str(&e.to_string()))?;
    std::future::pending::<()>().await;
    drop((listener, channel, pool));
    Ok(())
}

fn owner_command(
    state: &mut State,
    channel: &BroadcastChannel,
    kind: Kind,
    epoch: &str,
    client: &str,
    command: Command,
) -> Result<Reply, Error> {
    match command {
        Command::Query(query) => state.query(&query).map(Reply::Snapshot),
        Command::Write(mutations) => state.write(mutations).map(Reply::Commit),
        Command::Observe { id, query } => {
            let channel = channel.clone();
            let client = client.to_owned();
            let epoch = epoch.to_owned();
            let observation_id = id.clone();
            state.observe(
                format!("{client}:{id}"),
                query,
                Box::new(move |result| {
                    let Some(result) = result else {
                        return true;
                    };
                    post(
                        &channel,
                        kind,
                        Message::Snapshot {
                            client: client.clone(),
                            epoch: epoch.clone(),
                            id: observation_id.clone(),
                            result,
                        },
                    )
                    .is_ok()
                }),
            );
            Ok(Reply::Done)
        }
        Command::Unsubscribe(id) => {
            state.unsubscribe(&format!("{client}:{id}"));
            Ok(Reply::Done)
        }
    }
}
