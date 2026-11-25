import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import vm from "node:vm";

function loadExample(name, register) {
  const script = readFileSync(new URL(`../skill/flare-plugin/examples/${name}/plugin.js`, import.meta.url), "utf8");
  let definition;
  vm.runInNewContext(script, {
    [register](plugin) { definition = plugin; },
    flare: { error(value) { return new Error(value.message.value); } },
  });
  return definition;
}

test("timeline processor example attaches a link count to the input post", () => {
  const processor = loadExample("timeline-processor", "defineFlareTimelineProcessorPlugin");
  const result = processor.processBatch({
    locale: "en",
    trigger: "manualRefresh",
    items: [{
      itemId: "item-1",
      platformId: "Example",
      postKey: { id: "post-1", host: "example.org" },
      createdAt: "2026-01-01T00:00:00Z",
      contentPlain: "Two links",
      links: ["https://example.org/one", "https://example.org/two"],
      sourceLanguages: ["en"],
    }],
  });
  assert.deepEqual(structuredClone(result.results), [{
    itemId: "item-1",
    cards: [{ id: "link-count", body: [{ type: "stat", label: "Links", value: "2" }] }],
  }]);
});

test("manga reader example opens guest chapters and resolves a deferred page", () => {
  const { manga, timeline } = loadExample("manga-reader", "defineFlareSocialPlugin").capabilities;
  const { items } = timeline.page({ timelineId: "reader", page: { limit: 20 }, parameters: {} });
  const { post } = items[0];
  assert.equal(post.detailType, "manga");

  const detail = manga.detail({ key: post.key });
  assert.equal(detail.cover.type, "image");
  const chapterId = detail.chapters[0].id;
  const { pages, endReached } = manga.pages({ key: post.key, chapterId });
  assert.equal(endReached, true);
  assert.equal(pages[0].media.type, "image");

  const deferred = pages[1];
  assert.equal(deferred.media, undefined);
  const request = { key: post.key, chapterId, pageId: deferred.id, pageToken: deferred.pageToken };
  const media = manga.pageImage(request);
  assert.equal(media.type, "image");
  assert.equal(media.id, deferred.id);
  assert.throws(() => manga.pageImage({ ...request, pageToken: "unknown" }), /Unknown page/);
});
