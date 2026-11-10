# Existing-platform action example

This Host API v2 package adds a read-only login-session check for an authorized
built-in Weibo (`VVo`) account. Install it, open **More → Plugin tasks**, grant
an account, and select **Run now**. Daily foreground execution is optional and
uses `Asia/Shanghai` dates. The `/api/config` request matches Flare's current Weibo
implementation; this example has not been verified with a real account.

Build the package with `node ../../pack.mjs . example.fpp` from this directory.
The package can also include an offline settings page and other registrations.

This example does not perform super-topic check-ins. For a check-in plugin,
verify the website's endpoints, CSRF requirements and already-completed response
before implementing its action. Store target lists and per-target checkpoints
through plugin storage; use `context.accountId` to separate account state.

For other plugins' platforms, the source social plugin must opt in with
`flare.datasource.account-http/v1`; consumers keep using the same account HTTP
API. The [extension contract](../../references/platform-extensions.md) includes
the provider registration, `$accountOrigin` targets, per-platform actions and
the rules for reauthorization after source updates.
