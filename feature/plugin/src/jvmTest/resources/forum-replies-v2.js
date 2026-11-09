// Synthetic v2 adapters using the pinned plugins' ID shapes; /record is a mock, not a website API.
const kind = "__PLATFORM__";
const rootId = "2050290260991534603";
const floorPid = "2050290260991534604";
const firstId = "2050290260991534605";
const secondId = "2050290260991534606";
const thirdId = "2050290260991534607";
const fourthId = "2050290260991534608";
const nextCursor = "/fixture/comments?offset=a+b/c=&page=21";
function key(id, context) { return { id, host: context.origin.slice(8) }; }
function commentKey(id, context) {
  return key(kind === "zhihu" ? "comment:pin:" + rootId + ":" + floorPid + ":" + id :
    id === floorPid ? "p" + rootId + "_" + floorPid : "subreply:" + rootId + ":" + floorPid + ":" + id, context);
}
function decode(id) {
  const match = (kind === "zhihu" ? /^comment:pin:(\d+):(\d+):(\d+)$/ : /^subreply:(\d+):(\d+):(\d+)$/).exec(id);
  if (match) return { rootId: match[1], floorPid: match[2], replyToId: match[3] };
  const floor = /^p(\d+)_(\d+)$/.exec(id);
  if (kind === "tieba" && floor) return { rootId: floor[1], floorPid: floor[2], replyToId: floor[2] };
  throw flare.error({ code: "NotFound", message: { value: "Unknown comment" } });
}
async function record(value, context) {
  await flare.http.request({ url: context.origin + "/record", method: "POST", body: { type: "json", value } });
}
function post(id, context, liked = false) {
  const result = {
    key: commentKey(id, context),
    author: { key: key("author", context), handle: "fixture", displayName: "Fixture author" },
    createdAt: id === firstId ? "2020-01-01T00:00:00Z" : "2026-01-01T00:00:00Z",
    content: { value: "A forum comment " + id },
    detailType: "link", url: context.origin + "/content/" + rootId,
    favouritesCount: liked ? 1 : 0,
    actions: [{ action: liked ? "Unfavourite" : "Favourite", active: liked, count: liked ? 1 : 0 }, { action: "Reply" }],
    target: { type: "timelinePage", pageId: "replies", title: { value: "Replies" },
      parameters: { kind, rootId, floorPid, parentId: id, sort: "hot" } }
  };
  if (id !== floorPid) result.replyTo = commentKey(
    id === secondId ? firstId : id === thirdId || id === fourthId ? secondId : floorPid, context);
  return result;
}
defineFlareSocialPlugin({ capabilities: {
  timelinePage: { async load(request, context) {
    const parameters = request.page.parameters;
    if (request.pageId !== "replies" || parameters.kind !== kind || parameters.rootId !== rootId || parameters.floorPid !== floorPid) {
      throw flare.error({ code: "Validation", message: { value: "Missing comment context" } });
    }
    await record({ operation: "page", request, accountId: context.accountId }, context);
    if (parameters.mode === "remote-offset") {
      const remotePage = [floorPid, firstId, secondId];
      const offset = request.page.direction === "refresh" ? 0 : JSON.parse(request.page.cursor).offset;
      const items = remotePage.slice(offset, offset + request.page.limit).map(id => ({ type: "post", post: post(id, context) }));
      const nextOffset = offset + items.length;
      return { items, endReached: nextOffset === remotePage.length,
        ...(nextOffset < remotePage.length ? { olderCursor: JSON.stringify({ pn: 1, offset: nextOffset }) } : {}) };
    }
    if (request.page.direction !== "refresh") {
      if (request.page.cursor === nextCursor) return { items: [], olderCursor: "21" };
      if (request.page.cursor !== "21") throw flare.error({ code: "Validation", message: { value: "Wrong continuation" } });
      return { items: [{ type: "post", post: post("2050290260991534607", context) }], endReached: true };
    }
    const children = parameters.parentId === secondId ? [thirdId, fourthId] : [firstId, secondId];
    const ids = parameters.sort === "latest" ? [...children].reverse() : children;
    return { items: [parameters.parentId, ...ids].map(id => ({ type: "post", post: post(id, context) })), olderCursor: nextCursor };
  } },
  post: {
    async detail(request, context) {
      const owner = kind === "zhihu" ? "pin:" + rootId : "t" + rootId;
      if (request.key.id === owner) return { post: { ...post(floorPid, context), key: request.key, target: undefined },
        presentation: { quotes: [post(floorPid, context)] } };
      await record({ operation: "detail", context: decode(request.key.id) }, context);
      return { post: post(decode(request.key.id).replyToId, context) };
    },
    async mutate(request, context) {
      const ids = decode(request.key.id);
      await record({ operation: "like", context: ids, key: request.key, action: request.action }, context);
      return { type: "updatedPost", post: post(ids.replyToId, context, request.action === "Favourite") };
    }
  },
  compose: { async publish(request, context) {
    if (!request.reference || request.reference.type !== "reply") throw flare.error({ code: "Validation", message: { value: "A reply reference is required" } });
    await record({ operation: "publish", context: decode(request.reference.key.id), reference: request.reference, text: request.text }, context);
    return { post: post("2050290260991534609", context) };
  } }
} });
