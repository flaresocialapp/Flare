# Translation and offline settings contract demo

Package this directory with `node ../../pack.mjs . demo.fpp` and install it in a Host supporting API v2. Select the demo in translation settings and open its settings page from plugin management on iOS, Android or macOS. Saving a prefix changes only the plugin-owned `options` namespace and expires this plugin's translations.

This example prefixes translatable tokens; it does not translate languages or call a remote service. Locked tokens and batch entity/field identities are preserved. The page has no remote dependencies. `settings/sdk.mjs` is the bundled settings SDK, copied from `../../settings-sdk.mjs`.
