defineFlareTimelineProcessorPlugin({
  processBatch({ items }) {
    return {
      results: items.map(({ itemId, links }) => ({
        itemId,
        cards: [{
          id: "link-count",
          body: [{ type: "stat", label: "Links", value: String(links.length) }],
        }],
      })),
    };
  },
});
