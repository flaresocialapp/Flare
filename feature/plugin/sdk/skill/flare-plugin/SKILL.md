---
name: flare-plugin
description: Create, extend, debug, and prepare GitHub distribution for Flare Plugin API v1/v2 plugins. Use when implementing a website adapter, editing a Flare plugin manifest or classic JavaScript entrypoint, adding text translation, plugin storage or an offline settings page, adding login or datasource capabilities, or diagnosing Flare plugin installation and runtime errors.
---

# Flare Plugin

Produce a Flare plugin that matches Flare's declared API contract and can be installed
from a public GitHub repository. Establish the requested capability combination;
use verified website evidence for its network features. This skill includes the SDK; resolve links relative to this file, not
to the user's plugin repository.

## Establish the contract

1. Inspect an existing plugin before changing it and establish its capability combination.
   For network features, collect the target HTTPS origin, login method, API documentation
   and representative redacted responses. Public API documentation may supply missing
   information. For offline settings-only plugins, establish metadata, packaged resources
   and the plugin-owned storage contract. Resolve endpoint, authentication, identity or
   paging assumptions before implementing the affected network feature.
2. Read [manifest](references/manifest.md) and the references for the requested
   branch: [capabilities](references/capabilities.md) and
   [runtime/HTTP](references/runtime-http.md) for social/background code, or
   [storage, translation and settings](references/storage-and-settings.md) for v2
   translation, plugin data and webpage settings. Map social features to
   capability IDs, their required operations, service method paths, and UI entry
   points. Declare only supported behavior, including any required companion
   operations. A function's TypeScript optionality does not relax manifest rules.
3. Use [flare-plugin.d.ts](flare-plugin.d.ts) for exact signatures and [ABI.md](ABI.md)
   for the boundary contract. Declare apiVersion 2 when using application response
   headers, manual redirects, conditional credential updates, or WebCookie pending
   state/metadata, plugin storage, translation or settings. Host API v2 is
   unreleased; select matching Host/SDK source revisions. Keep v1 manifests within
   their original behavior. If the target Flare build
   has a different contract, establish its version before generating code; there
   is no implied released-app minimum version in this skill.

For text translation, generic storage, combined plugins or offline settings pages, read
[storage, translation and settings](references/storage-and-settings.md). Use API v2,
keep data schemas inside the plugin, declare translation storage dependencies, and
bundle the page SDK and every webpage dependency under `settings/`. Settings-only
packages can omit the background entrypoint. Social-only adapters can retain v1/v2.

## Implement the requested plugin

For a social adapter, start with `npx flare-plugin-sdk@next init my-plugin` and follow the
[project workflow](assets/github-template/README.md). For manual copies, put the
**contents** of `assets/github-template/` at root, replace example IDs and rename
`_gitignore` to `.gitignore`. Install development dependencies, edit `src/plugin.ts`
using the ambient SDK types, and run `npm run build`. Commit the resulting root
`plugin.js` with the TS source and lockfile. `npm run dev` watches without type
checking; `npm run check` and the type-checked build are the release gates.
Its `/api/me` and `/api/posts` contract is illustrative, not a live service. Adapt
it from verified website evidence before presenting it as an integration.
Use [Pixelfed](examples/pixelfed/README.md) as a complete real-service example,
not as a universal API specification.

For translation or settings-only work, start from the
[offline interface example](examples/translation-settings/README.md) and its
[contract](references/storage-and-settings.md). Keep only the requested feature
declarations; settings-only packages can omit the background script. The CLI
starter remains a social adapter, not a requirement to add a platform or login.

Read the references needed for the task:

| Task | Read |
| --- | --- |
| Detection, OAuth, password, token import, form, Cookie, credential refresh, v2 pending/CAS | [Authentication](references/authentication.md) |
| Manual SSO redirects, response headers, body/header tokens, native retry limits | [Runtime/HTTP](references/runtime-http.md) |
| Text translation, namespace revisions, offline page SDK and cache dependencies | [Storage, translation and settings](references/storage-and-settings.md) |
| Map remote entities, HTML, media, identity, cursor pagination, opaque tokens | [Wire data](references/wire-data.md) |
| Implement any of the twelve capabilities, guest access, actions, Deep Links, localization | [Recipes](references/recipes.md) and the relevant [capability row](references/capabilities.md) |
| Diagnose a rejected package, missing UI, failed request, or retired runtime | [Troubleshooting and limits](references/troubleshooting.md) |
| Check the generated files and exercise behavior | [Testing](references/testing.md) |
| Prepare a GitHub repository, version tag, update, or schema change | [Publishing](references/publishing.md) |

When background code is needed, keep `plugin.js` self-contained and register once
with `defineFlarePlugin` or its social alias. Package settings page modules and
dependencies separately under `settings/`; bundle `flare-plugin-sdk/settings`
for the browser instead of importing it from the background entrypoint.
Use `flare.http.request` with structured authorization/Cookies; retain credentials
through the Host account credential API for social accounts, or plugin storage
for plugin-owned configuration. Map social responses explicitly into Wire
DTOs. Do not add fields to the ABI or assume browser/Node globals exist. Perform
Host calls inside registered methods, not during script initialization.

The manifest and registered methods must agree; social login's negotiated
capabilities must also match its declarations.
For social features, keep account IDs stable and use the selected origin for authenticated identity;
treat globals as disposable cache. Use typed `flare.error` results for expected
remote failures. Request media permission only when the adapter returns remote
media. Keep actual secrets out of source, examples, logs, and repository history.

## Validate and deliver

Follow [testing](references/testing.md): check syntax, manifest/method agreement,
representative mapping and paging behavior, and available real Host validation.
For an existing plugin, recheck the behavior changed by the edit. State what ran
and what still needs the target website or Flare UI; syntax checks and mocked
responses do not prove live integration.

Prefer GitHub distribution: a complete plugin at repository root or a directory
selected by `?path=/plugins/example`, already-built background/page resources, and a stable
SemVer repository snapshot tag. Each plugin has independent `manifest.version`
and unique plugin IDs plus stable platform IDs when declared. Prepare files
and release instructions; create/push repositories, commits, or tags only within
the user's current authorization. See [publishing](references/publishing.md) for
immediate activation, uninstall cleanup, and the optional local `.fpp` path.

Deliver the complete repository files, feature-to-capability mapping, setup and
installation instructions, checks performed, and remaining assumptions. A new
plugin is ready for use only when its declared background/page behavior and any
required website contracts have been exercised; otherwise identify the unfinished integration
steps precisely.
