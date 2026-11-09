# Translation and offline settings contract demo

From this directory, run `node ../../pack.mjs . ../translation-settings-demo.fpp`
and install the output in a matching Host API v2 build. Keep the output outside
the input directory so packaging can be repeated. Select the demo in translation
settings and open its page from the plugin item's **More → Settings** menu on
iOS, Android or native macOS. Saving a prefix changes the plugin-owned `options` namespace and
expires this plugin's translations; unrelated `state` data is not a dependency.

This example prefixes translatable tokens; it does not translate languages or call a remote service. Locked tokens and batch entity/field identities are preserved. The page has no remote dependencies. `settings/sdk.mjs` is the bundled settings SDK, copied from `../../settings-sdk.mjs`.

To adapt the layout into a settings-only plugin, omit `translation` and `plugin.js`,
give the plugin its own ID/name, and replace the prefix form with the requested
configuration. To implement a real translator, replace the background demo
methods, declare its HTTPS service origins and keep service configuration in
plugin storage. Read the [complete contract](../../references/storage-and-settings.md)
for snapshot revisions, cache dependencies and the browser SDK boundary.
