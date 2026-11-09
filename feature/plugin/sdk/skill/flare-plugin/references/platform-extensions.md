# Existing-platform actions

Use Host API v2 and `defineFlarePlatformExtensionPlugin({ actions: { actionId } })`
to add operations to existing built-in accounts. This registration may coexist
with social, translation and settings capabilities; call each entrypoint once
synchronously. Extensions do not own or replace a platform and cannot intercept
timelines or inject post/profile menus.

```json
{
  "apiVersion": 2,
  "id": "org.example.actions",
  "version": "0.1.0",
  "name": "Account actions",
  "platformExtension": {
    "apiVersion": 1,
    "targets": [{ "platformId": "VVo", "authOrigins": ["https://m.weibo.cn"] }],
    "actions": [{ "id": "runDaily", "title": "Daily action", "dailyTimeZone": "Asia/Shanghai" }]
  }
}
```

Declare 1–8 unique targets and 1–16 unique actions. Action IDs begin with a letter
and contain only letters, numbers and underscores (at most 64 characters);
prototype names are reserved. Target origins are exact canonical HTTPS origins,
1–16 per platform. Titles and optional descriptions support normal localized
plugin text. Omit `dailyTimeZone` for manual-only actions; a supplied IANA timezone
must be recognized by the Host. Every action requires `plugin.js` and its matching
`platformExtension.actions.<id>` handler.

## Authorization and HTTP

On native iOS, Android and macOS, **More → Platform actions** lists compatible
accounts. Users grant each account explicitly and independently enable each daily
action. A platform must supply a native authentication adapter. Initially Weibo
`VVo` supports `https://m.weibo.cn` and `https://weibo.com`; no other built-in or
plugin-owned platform automatically grants authentication access. All requested
origins must be supported by the platform.

Inside an action, `context.scope` is `extension`, `context.targetPlatformId` is
the target platform, and `context.accountId` is an opaque per-plugin grant handle.
The normal `context.platformId` remains the package's own social-platform ID or
an empty string. Do not assume the grant handle is a website UID. Re-granting an
account generates a new handle. Never rely on globals for persistent state.

Use `flare.account.http.request(request)` for authenticated requests. Host
authentication is injected only for this action's target origins, with the target
account's proxy and active-session checks on every request and redirect hop.
Request cookies/authorization overrides are rejected. Authentication headers and
`Set-Cookie` response headers are not exposed to JavaScript; response bodies are
available to the plugin. Origin permission permits arbitrary account operations
at those origins, including writes; it does not restrict the plugin to its action
title. The Host authorization UI discloses this scope.

Weibo uses its current Cookie and proactive refresh flow; the Host injects its
Cookie-derived `X-XSRF-TOKEN` header when available. A website can additionally
require a CSRF token obtained from an authenticated response; the plugin handles
that protocol. The Host never blindly retries an action's remote writes.

`flare.http.request` remains unauthenticated and uses `permissions.httpOrigins`.
It never obtains the selected account's authentication. `flare.credential` is
unavailable in extension scope. Settings pages retain storage and unauthenticated
HTTP only; account grants and execution are controlled by native UI.

Logout/removal/re-login revokes the old session, including an in-flight action.
Ordinary credential refresh preserves the session. Adding targets, authentication
origins, action IDs, schedules or ordinary HTTP permissions requires a new account
grant. Installation/update also discloses authenticated origins. A stale native
authorization review is rejected after account/session or package changes.

## Execution and results

An action receives `{ actionId, businessDate, trigger }`; trigger is `manual` or
`automatic`, and businessDate is `YYYY-MM-DD` in its schedule timezone (the device
timezone for manual-only actions). Return:

```js
{ status: "completed", succeeded: 3, skipped: 2, failed: 0 }
```

Status is `completed`, `already-completed`, `partial`, `failed`,
`authentication-required`, `user-action-required` or `skipped`. Counts are optional
integers from 0 to 100,000. Authentication/manual-verification results pause all
automatic actions for that grant. Host timeout, cancellation, interruption and
invalid results are handled without recording exception/request/response text.

Manual execution belongs to the application, so closing its UI does not cancel it.
The Host runs actions only while the app is foregrounded. Opening/returning to
the app or enabling automation attempts a due action. It persists the date
before execution, serializes each plugin/account, and automatically attempts each
action at most once per business date. Failed, partial and interrupted attempts
require a manual retry. Daily deduplication uses stable platform/account/action identity
and survives re-granting, re-login and permission changes. Recent dates are retained
independently of grants, with a 4,096-entry limit; saturation rejects a new attempt
before remote effects. Fifty recent results are retained separately from daily
deduplication; removing old history cannot trigger a repeat. This is not a
guarantee of exactly-once remote effects. Query remote completion before retrying
an uncertain write, and use plugin storage for per-target progress. Multi-device
deduplication requires the remote platform's completion state.

The existing runtime timeout applies; at most 64 HTTP Host calls are allowed per
action, including unauthenticated requests. Leaving the foreground cancels work.
Disabling/removing the plugin, revoking a grant or retiring its runtime also
invalidates execution. Uninstall removes Host grants/history and plugin storage
without deleting the built-in account. There is no OS background scheduler,
Compose Desktop integration or Web support for this capability.

See the [read-only Weibo example](../examples/platform-extension/README.md).
