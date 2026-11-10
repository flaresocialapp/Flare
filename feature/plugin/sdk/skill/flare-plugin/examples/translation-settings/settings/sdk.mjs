/** Bundle this module and all page dependencies into the plugin's settings/ directory. */
let connection;

export function connectSettings() {
  return connection ??= connect();
}

async function connect() {
  const page = globalThis.window;
  if (!page || page !== page.top) throw new Error("Open this page in Flare plugin settings");
  const pending = new Map();
  let nextId = 0;
  let closed = false;
  const android = page.__flareNative;
  const apple = page.webkit?.messageHandlers?.flareSettings;
  const transport = android ?? apple;
  if (!transport?.postMessage) throw new Error("Flare settings bridge is unavailable");

  function receive(input) {
    const response = typeof input === "string" ? JSON.parse(input) : input;
    const task = pending.get(response?.id);
    if (!task) return;
    pending.delete(response.id);
    clearTimeout(task.timeout);
    if (response.ok === true) task.resolve(response.value);
    else task.reject(Object.assign(new Error(response.error?.message ?? "Host request failed"), { code: response.error?.code ?? "bridge.failed" }));
  }
  Object.defineProperty(page, "__flareSettingsReceive", { value: receive, configurable: false, writable: false });
  if (android) android.onmessage = event => receive(event.data);
  page.addEventListener("pagehide", () => {
    closed = true;
    for (const task of pending.values()) {
      clearTimeout(task.timeout);
      task.reject(Object.assign(new Error("Settings page closed"), { code: "bridge.closed" }));
    }
    pending.clear();
  }, { once: true });

  function call(operation, args = {}) {
    if (closed) return Promise.reject(new Error("Settings page closed"));
    if (pending.size >= 32) return Promise.reject(new Error("Too many pending Host requests"));
    const id = String(++nextId);
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        pending.delete(id);
        reject(Object.assign(new Error("Host request timed out"), { code: "bridge.timeout" }));
      }, 35_000);
      pending.set(id, { resolve, reject, timeout });
      try { transport.postMessage(JSON.stringify({ id, operation, arguments: args })); }
      catch (error) { clearTimeout(timeout); pending.delete(id); reject(error); }
    });
  }

  const hello = await call("bridge.hello", { version: 1 });
  if (hello?.version !== 1) throw new Error("Unsupported Flare settings protocol");
  return Object.freeze({
    storage: Object.freeze({ open: namespace => Object.freeze({
      read: keys => call("storage.read", { namespace, keys }),
      write: (changes, options) => {
        JSON.stringify(changes.set ?? {}, (_, value) => {
          if (value === undefined || ["function", "symbol", "bigint"].includes(typeof value) || (typeof value === "number" && !Number.isFinite(value))) {
            throw new TypeError("Storage values must be JSON values with finite numbers");
          }
          return value;
        });
        return call("storage.write", { namespace, changes, expectedRevision: options?.expectedRevision });
      },
    }) }),
    http: Object.freeze({ request: request => call("http.request", request) }),
  });
}
