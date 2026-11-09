# Storage, text translation and offline settings

Use Host API `apiVersion: 2` for storage, translation and settings. This extends the unreleased v2 contract; API v1 social plugins remain compatible. `platform`, `translation` and `settings` are independently optional; provide at least one. Only background capabilities require `plugin.js`. Register social methods with `defineFlareSocialPlugin` and translation methods with `defineFlareTranslatePlugin`, each at most once. A combined package calls both synchronously in either order. Settings-only packages need neither call.

Use matching Host and SDK source revisions; this document does not promise a
published app/npm version. The translation interface and settings bridge each
use version 1 independently of Host API v2.

## Settings-only package

The minimal manifest is:

```json
{
  "apiVersion": 2,
  "id": "org.example.settings",
  "version": "0.1.0",
  "name": "Example settings",
  "permissions": { "httpOrigins": ["https://api.example.org"] },
  "settings": { "entry": "settings/index.html", "bridgeVersion": 1 }
}
```

Package that manifest, one supported `assets/icon.<extension>`, and all page
resources under `settings/`. No platform, login methods or `plugin.js` are
required. Omit `httpOrigins` when the page makes no remote requests; a configured
endpoint alone never grants access to it. This example origin also authorizes
the SDK HTTP example below. Add `translation` and its two background methods
when the plugin should appear in Flare's translation-provider selector.

## Opaque plugin storage

Both background methods and settings pages use the same plugin-owned namespaces:

```js
const store = flare.storage.open("options");
const snapshot = await store.read();
await store.write({ set: { model: "example" }, remove: ["retired"] }, {
  expectedRevision: snapshot.revision,
});
```

Namespace names, keys, JSON values, data schemas and migrations belong to the plugin. Storage belongs to the plugin installation, shared by its capabilities and accounts. The Host binds the plugin identity; callers cannot select another plugin or access a filesystem path. Account credentials remain a separate account-scoped contract.

`read(keys?)` returns `{ values, revision }` from one namespace snapshot. Omit keys to read all entries. Missing keys are absent; JSON null is a stored value. `write({set?, remove?}, {expectedRevision?}?)` atomically patches one namespace and returns `{revision}`. A key in both set and remove is invalid. Conditional writes reject with `storage.conflict` if another writer committed; reread and resolve the conflict before retrying. Unconditional patches apply in commit order. No-op writes preserve the revision. Successful content changes persist a fresh opaque revision before notifying observers.

Limits: 64 namespaces and 4,096 keys per plugin; 256 UTF-8 bytes per namespace/key name with no control characters; 1 MiB per JSON value, 4 MiB total encoded storage, and nesting depth 64. Strings must be valid Unicode and numbers must be finite. Storage errors include `storage.invalid`, `storage.quota`, `storage.conflict`, `storage.corrupt`, `storage.io` and `storage.unavailable`. Validation, conflict and quota failures preserve existing data. Once a commit starts it completes atomically even if the caller closes the page; reread storage if its acknowledgement was lost. Unreadable data is reported rather than reset. Treat stored values as sensitive and keep them out of logs. Updates preserve storage; uninstall removes it. Plugins manage schema compatibility, including downgrades.

## Text translation

```json
{
  "translation": {
    "apiVersion": 1,
    "cache": { "storageNamespaces": ["options"] }
  }
}
```

Pass the two implemented handlers directly, without a `translation` wrapper:

```js
defineFlareTranslatePlugin({
  translateDocumentJson,
  translateBatchDocumentJson,
});
```

Social detection, login and capabilities belong in a separate
`defineFlareSocialPlugin` call when supplied. Duplicate registrations, registering
translation through the social entrypoint, and registrations after initialization
are rejected. Both types share the package identity, storage namespaces and
manifest permission limits.

The Host invokes these methods internally as `translation.translateDocumentJson(request, context)` and `translation.translateBatchDocumentJson(request, context)`. Both return a JSON string or null. Throw `flare.error(...)` for expected failures. The Host retains its existing translation, platform-preference, batching, retry and error policies. Image translation is outside this interface.

The single-document request contains `sourceTemplate`, `sourceJson`, `targetLanguage` and `prompt`. `sourceJson` encodes `{version, targetLanguage, blocks:[{id,tokens:[{id,kind,text}]}]}`. Preserve block/token IDs and `Locked` tokens, replacing the text of `Translatable` tokens. Return the translated document as a JSON string.

The batch request contains `sourceJson`, its parsed equivalent `sourceDocument`, `targetLanguage` and `prompt`. Its document has `items:[{entityKey,status,payload,reason?}]`; payload contains optional `content`, `contentWarning`, `title` and `description` translation documents. Preserve entity keys and fields. Return the same document shape with translated fields, or existing `Skipped` item semantics. Implement the remote calls, models, prompts and algorithms as needed inside these functions. Plugin-scoped calls have no account credentials or account origin; HTTP uses declared `permissions.httpOrigins` and the global proxy.

The Host's cache identity includes plugin ID, package/code generation and the revisions of declared namespaces. Omitted/null `storageNamespaces` means all plugin storage; `[]` means no storage dependency. The plugin chooses which data affects translation. Put volatile tokens, counters and test results in namespaces outside the dependency list when they do not affect the translated content. A change to a dependency also expires an in-progress translation; a translation that writes its own dependency expires its own result. The Host rejects stale results before cache writes without restarting social account sessions.

Read configuration during each translation invocation, or compare its snapshot
revision before reusing plugin-side cached configuration. Host cache invalidation
does not refresh JavaScript variables managed by the plugin.

## Offline settings page

On iOS, Android and native macOS, open the plugin item's **More → Settings** menu
to show its packaged page. Plugins without a page have no Settings menu item.

```json
{ "settings": { "entry": "settings/index.html", "bridgeVersion": 1 } }
```

Build HTML, JS, CSS, fonts and supported raster images into `settings/`. Relative subdirectories are allowed; paths are ASCII and traversal/symlinks are rejected. Include every dependency in the package. The SDK package exports `flare-plugin-sdk/settings` for bundlers, with TypeScript types:

```js
import { connectSettings } from "flare-plugin-sdk/settings";
const sdk = await connectSettings();
const store = sdk.storage.open("options");
const snapshot = await store.read();
await store.write({ set: { endpoint: "https://api.example.org" } }, {
  expectedRevision: snapshot.revision,
});
const response = await sdk.http.request({ url: "https://api.example.org/models" });
```

For plain JS, copy `settings-sdk.mjs` into the packaged settings directory and import it by relative path. The SDK connects only in a top-level Flare settings page, negotiates bridge version 1, and exposes storage and restricted HTTP. The Host does not define connection tests, model selectors, language queries or form schemas. `sdk.http.request` uses the existing API v2 HTTP request/response format, including application headers and manual redirects; allowed origins come from the manifest. It has no account cookies or account credentials.

The background entrypoint has no DOM or browser imports. The settings page has
its own browser module build: resolve npm imports while bundling and ship the
result with the page. The social starter copies already-built `settings/` files;
it does not compile page TypeScript. Bind forms with JavaScript event listeners
and prevent their default navigation before saving through the SDK.

Load scripts from package files; inline script, eval, remote resource loading, WebRTC, frames, workers, form navigation and filesystem access are unavailable. Native containers apply a Host-controlled CSP and sandbox with an opaque origin. The bridge authenticates the main document and its unique per-page URL; child frames have no Host access. Native document-start scripts disable WebRTC, including legacy constructor aliases. Browser storage is unavailable; use the plugin storage API. Use `sdk.http.request` for remote requests, and inspect response status yourself. Save behavior belongs to the page: only a completed write persists data. Closing or replacing the page revokes its session and cancels pending requests. Android requires WebView message-listener, document-start script and multi-profile support; its dedicated settings profile keeps browsing data separate from login WebViews. Native support is iOS, Android and macOS; Compose Desktop and Web have no settings page integration.

See [the translation/settings example](../examples/translation-settings/README.md) for a complete offline package and JSON transformation demo. It demonstrates the interface rather than a real translation provider.
