const assetBase = "https://raw.githubusercontent.com/flaresocialapp/Flare/1.8.0/docs/";
const seriesKey = { id: "sample-manga", host: "example.org" };
const chapter = { id: "chapter-1", title: "Chapter 1" };
const deferredPage = { id: "page-2", pageToken: "sample-page-2" };

function image(id, filename) {
  return { id, type: "image", url: assetBase + filename };
}

function notFound(message) {
  return flare.error({ code: "NotFound", message: { value: message } });
}

function requireSeries(key) {
  if (key.id !== seriesKey.id || key.host !== seriesKey.host) {
    throw notFound("Unknown series");
  }
}

function requireChapter(key, chapterId) {
  requireSeries(key);
  if (chapterId !== chapter.id) {
    throw notFound("Unknown chapter");
  }
}

defineFlareSocialPlugin({
  capabilities: {
    timeline: {
      page() {
        return {
          items: [{
            type: "post",
            post: {
              key: seriesKey,
              author: { key: { id: "demo", host: seriesKey.host }, handle: "demo", displayName: "Demo author" },
              createdAt: "2026-01-01T00:00:00Z",
              content: { value: "Open the sample manga and select Chapter 1." },
              detailType: "manga",
            },
          }],
          endReached: true,
        };
      },
    },
    manga: {
      detail({ key }) {
        requireSeries(key);
        return {
          key: seriesKey,
          title: "Sample manga",
          cover: image("cover", "android-chrome-512x512.png"),
          readingDirection: "rtl",
          chapters: [chapter],
        };
      },
      pages({ key, chapterId }) {
        requireChapter(key, chapterId);
        return {
          pages: [
            { id: "page-1", media: image("page-1", "feature8.png") },
            deferredPage,
          ],
          endReached: true,
        };
      },
      pageImage({ key, chapterId, pageId, pageToken }) {
        requireChapter(key, chapterId);
        if (pageId !== deferredPage.id || pageToken !== deferredPage.pageToken) {
          throw notFound("Unknown page");
        }
        return image(pageId, "feature2.png");
      },
    },
  },
});
