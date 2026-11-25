# Timeline widget example

Link Count adds a native statistic card to timeline posts containing HTTPS links.
Flare filters posts using `requireLinks` before calling `processBatch`. The
plugin uses the links in each input item and returns a card against that item's
`itemId`. It works offline.

Use a [Flare build with timeline post processing](../../README.md#match-flare-and-the-sdk).
From the SDK skill directory, package the example.

```sh
node pack.mjs ./examples/timeline-processor /tmp/flare-link-count.fpp
```

Import the file in **Settings → Plugins** and approve timeline post access.
Refresh a timeline that contains a post with an HTTPS link. A **Links** card
appears on the matching post when processing is enabled.

See the [processor manifest](../../references/manifest.md#timeline-processor)
and [widget ABI](../../ABI.md#timeline-post-processing) for more filters, card
types, actions and cache options.
