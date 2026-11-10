# Example Feed plugin template

This is a GitHub repository template for a token-based Flare API v1 adapter.
The website API below is an **illustrative contract**, not an available service.
Adapt the paths, authentication, and mapping to your website before publishing.
No demonstration server is included. Tests in Flare exercise this template with
fixture HTTP responses through the real QuickJS runtime.

The starter remains a social adapter. To add API v2 translation or an offline
settings page, use the matching Host/SDK revision and follow the
[storage, translation and settings contract](https://github.com/flaresocialapp/Flare/blob/feature/plugin-v2/feature/plugin/sdk/skill/flare-plugin/references/storage-and-settings.md).
Keep ready-to-load HTML/CSS/JS and all page dependencies in `settings/`; the
starter's build/package commands copy that directory but do not compile page
TypeScript or resolve browser imports. Bundle the page SDK before packaging.
A settings-only project can omit the social platform and background script;
the standalone packer accepts that layout without this starter's build pipeline.

## 1. Adapt the plugin

If you created this project with `npx flare-plugin-sdk@next init my-plugin`, work
inside `my-plugin` and keep its generated plugin/platform IDs. If you copied the
template manually, first copy its contents to your plugin directory and replace
the example `manifest.id`, `platform.id`, and names. Keep IDs stable for updates.
For manual copies, also rename `_gitignore` to `.gitignore`; `init` does this for you.

The generated manifest's `$schema` is pinned to the SDK version that created it.
Open `manifest.json` in VS Code or another JSON Schema editor for completion,
field descriptions, enum choices and static errors. Flare treats the URL as
metadata and never downloads it; actual registration and behavior are checked in
Flare. See the [manifest editor guide](https://github.com/flaresocialapp/Flare/blob/feature/plugin-v2/feature/plugin/sdk/skill/flare-plugin/references/manifest.md#json-schema-and-editor-support)
for offline use and older Flare builds that reject `$schema`.

1. Update the description, login methods, and declared capabilities in
   `manifest.json`. Replace the one-pixel placeholder PNG with your own icon.
2. Adapt `src/plugin.ts` using the
   [developer guide](https://github.com/flaresocialapp/Flare/tree/feature/plugin-v2/feature/plugin/sdk/skill/flare-plugin)
   and your website's API evidence.
   It currently verifies an imported bearer token with `/api/me`, then reads
   `/api/posts`. It registers no detector, so choose the platform explicitly.
3. Update this README with the actual website, token acquisition steps, supported
   features, verification results, and remaining limitations.

The template requests no media permission and emits text-only posts. Every post
uses `detailType: "link"`, opening its HTTPS website URL. There is no arbitrary
profile lookup, internal post detail, write capability, or guest access. The
authenticated profile returned at login supplies the saved account identity.

## 2. Develop and debug locally

The root contains a prebuilt, installable `plugin.js`, alongside TS source and
development tools. With Node 22 or newer, run from this project directory:

```sh
npm install
npm run check
npm run build
node -e 'JSON.parse(require("node:fs").readFileSync("manifest.json", "utf8")); console.log("Manifest JSON parses")'
```

`npm install` creates a lockfile; commit it and use `npm ci` on later checkouts.
The private package uses the SDK for ambient types, TypeScript for strict checking,
and esbuild to bundle local TS modules into one ES2020 IIFE at root. The generated
SDK dependency is version-pinned. Types such as `TimelineItemV1`, `PostV1`, `ProfileV1`, and
`PluginErrorCodeV1` are available directly, and `defineFlareSocialPlugin` types its
handlers.
The SDK is not imported at runtime; plugin code has no Node or browser globals.

Flare supplies the `defineFlareSocialPlugin` and `flare` globals and performs the actual
manifest, registration, and runtime checks. Use a Flare build with Plugins in Settings.

1. In Compose Desktop or native macOS, open **Settings → Plugins → More → Plugin
   development**. Choose this directory, click **Start development**, and confirm
   the access review.
2. Add an account, explicitly select this plugin platform, and enter your website's
   HTTPS API origin. Import a real access token. After adapting the endpoints,
   verify the account identity, Home refresh, older/empty/end pages, and link clicks.
3. Run `npm run dev` and edit `src/plugin.ts` or its local modules. Watch mode
   rebuilds `plugin.js`, preserving the previous script on syntax/build errors.
   It does not check types; use editor diagnostics or `npm run check` separately.
   Flare automatically validates the built script, manifest, icon and translations;
   click **Reload plugin** to apply the validated version. Invalid changes leave
   the previous working version running.
4. Check validation errors and recent-call diagnostics in the development panel.
   Recent calls show method names and outcomes, without HTTP request/response
   payloads. Reproduce authentication, HTTP, response-mapping, and paging failures
   against your website's documented API.
5. Click **Stop development** before installing or updating this plugin. Stopping
   restores the version that was running before development; app restarts do not
   automatically mount this directory. Development uses real accounts and data,
   and remote writes are not rolled back.

Keep the plugin directory selected in Flare. Edit TS source, not the generated
`plugin.js`; `npm run build` checks types before rebuilding it. Commit that built
script at the plugin directory root, even when that directory is nested in a
larger repository, so GitHub installation works without running npm on the device.
See [development and testing details](https://github.com/flaresocialapp/Flare/blob/feature/plugin-v2/feature/plugin/sdk/skill/flare-plugin/references/testing.md#develop-locally-in-desktop)
for reload restrictions, credential changes, and platform acceptance checks.

## 3. Publish and install through GitHub (recommended)

Create a public GitHub repository with these ready-to-run files in the selected
plugin directory (the repository root by default):

```text
manifest.json
plugin.js
assets/icon.png
README.md
locales/en.json       # optional; other locale catalogs as needed
```

The icon can also be JPEG, WebP, GIF, or ICO; keep exactly one supported icon.
Development files may coexist in the repository, but keep credentials,
`node_modules`, build caches, and private fixtures out of Git. Flare reads the
committed plugin files directly. Run `npm run build` before committing/tagging;
it must pass and the latest `plugin.js` must be committed. Keep the TS source,
build configuration and lockfile in Git too. `.gitignore` excludes dependencies
and local archives while retaining the runtime script.

For a new project and an empty GitHub repository, replace `OWNER/REPO` and run:

```sh
git init
git add manifest.json plugin.js assets src package.json package-lock.json tsconfig.json build.mjs .gitignore README.md
# If used, also stage translations with: git add locales
git commit -m "Add Flare plugin"
git branch -M main
git remote add origin https://github.com/OWNER/REPO.git
git push -u origin main
```

Use the existing Git setup if the project is already a repository. The generated
`manifest.version` is `0.1.0`. After pushing the built files, publish a stable repository snapshot tag:

```sh
git tag v0.1.0
git push origin refs/tags/v0.1.0
```

Commit/push each changed plugin's manifest version and built files before tagging.
The repository snapshot tag, each plugin's version, and the npm SDK version are
independent. Flare ignores prerelease repository tags; a branch alone is
insufficient.
A GitHub Release and uploaded `.fpp` asset are unnecessary.

Stop development, then in **Settings → Plugins → Add → Install from GitHub** enter
`https://github.com/OWNER/REPO` (append `?path=/plugins/example` for a subdirectory).
Flare selects the latest stable repository snapshot; review its source
and permissions, and confirm. Installation takes effect immediately. Select the
plugin platform, HTTPS API origin, and real access token to verify it on each target
device. For later updates, increment `manifest.version`, commit/push, and publish
a newer stable repository snapshot tag while preserving IDs and directory.
Check for updates in Flare and review/confirm the new version. See the
[publication and update rules](https://github.com/flaresocialapp/Flare/blob/feature/plugin-v2/feature/plugin/sdk/skill/flare-plugin/references/publishing.md).

Several plugins can share one repository. Each directory must contain a complete
plugin with unique plugin/platform IDs. Install a subdirectory using
`https://github.com/OWNER/REPO?path=/plugins/example`; omit `path` for root.
The latest stable tag selects the repository snapshot, while each plugin keeps
its own manifest version. Unchanged runtime files do not offer updates.
Downloads over 20 MiB ask for confirmation; see the publishing guide for limits.

## Alternative: package a local file

Use `.fpp` for offline transfer or local testing. From this project directory:

```sh
npm run package
```

This checks types, rebuilds the root script, copies manifest/script/icon/locale and built settings
files to temporary staging, and uses the SDK packer to write `plugin.fpp` at root.
Staging is cleaned up and development files are excluded. The standalone packer
still accepts only a clean runtime directory, not this whole project.

Stop development for this plugin, then choose file installation in **Settings →
Plugins → Add**, select the `.fpp`, review, and confirm. It becomes available
immediately. The packer checks archive structure; Flare still validates the
manifest, registration, permissions, and behavior. Local file installs have no
GitHub source/update tracking.

## Illustrative website API to replace

The selected origin is an absolute HTTPS origin such as `https://api.example.org`
with no path. Both requests use a structured bearer token. The imported token
is accepted only after the profile request succeeds.

`GET /api/me`:

```json
{ "id": "alice", "username": "alice", "name": "Alice" }
```

`GET /api/posts?limit=20` and subsequent `&cursor=<encoded opaque cursor>`:

```json
{
  "items": [{
    "id": "post-1",
    "author": { "id": "alice", "username": "alice", "name": "Alice" },
    "created_at": "2026-01-01T00:00:00Z",
    "text": "A post from the website",
    "url": "https://example.org/posts/post-1"
  }],
  "next_cursor": "page-2"
}
```

`next_cursor` is omitted or null at the end. Empty pages may still contain a next
cursor. IDs are strings and stable across requests; request cursors never become
entity IDs. A refresh starts without the old cursor. Adapt these assumptions to
the actual API, including token expiry and rate-limit responses. The helper maps
401/404/429 to typed errors, treats other HTTP failures as `Remote`, and parses
the response's string `body` explicitly.

Describe separately which checks passed with fixtures and which passed against
the actual website and Flare UI. Keep tokens and Cookies out of the repository.
