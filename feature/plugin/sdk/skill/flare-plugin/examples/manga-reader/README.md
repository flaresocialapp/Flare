# Manga reader example

This plugin supplies a guest timeline with a single manga entry. The entry
opens native manga detail. The chapter returns one direct image and one
`pageToken` that `manga.pageImage` resolves in a worker. The series and chapter
come from static fixtures. Flare loads the images from its public GitHub
repository under the media network permission.

Use a [Flare build with manga and standalone guest support](../../README.md#match-flare-and-the-sdk).
From the SDK skill directory, package the example.

```sh
node pack.mjs ./examples/manga-reader /tmp/flare-manga-reader.fpp
```

Import the file in **Settings → Plugins** and approve media access. Add the
**Manga Reader Sample** guest timeline to Home, open its post, and select
**Chapter 1**. The timeline and chapter are available without an account.

See the [manga recipe](../../references/recipes.md#manga) and
[paging and image resolution](../../references/wire-data.md#manga-pagination-and-parallel-image-resolution)
for adapting the sample to a real service.
