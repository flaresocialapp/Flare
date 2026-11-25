import assert from "node:assert/strict";
import { execFileSync, spawn } from "node:child_process";
import { once } from "node:events";
import { cpSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";
import Ajv from "ajv";
import vm from "node:vm";

const sdk = fileURLToPath(new URL("../", import.meta.url));
const metadata = JSON.parse(readFileSync(join(sdk, "package.json"), "utf8"));

test("npm tarball preserves the SDK and creates a typed, buildable plugin project", { timeout: 60000 }, async (t) => {
  const directory = mkdtempSync(join(tmpdir(), "flare-plugin-sdk-"));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const toolingCache = execFileSync(process.execPath, [process.env.npm_execpath, "config", "get", "cache"], { encoding: "utf8" }).trim();
  const npm = (args, cwd = directory, cache = join(directory, "cache")) =>
    execFileSync(process.execPath, [process.env.npm_execpath, ...args], {
      cwd,
      encoding: "utf8",
      env: {
        ...process.env,
        npm_config_cache: cache,
        npm_config_update_notifier: "false",
      },
    });

  const [archive] = JSON.parse(npm(["pack", "--json", "--ignore-scripts", "--pack-destination", directory], sdk));
  const resources = readdirSync(join(sdk, "skill"), { recursive: true })
    .filter((path) => statSync(join(sdk, "skill", path)).isFile())
    .map((path) => `skill/${path.split(sep).join("/")}`);
  assert.deepEqual(
    archive.files.map(({ path }) => path).sort(),
    ["LICENSE", "README.md", "package.json", "bin/cli.mjs", ...resources].sort(),
  );
  assert.equal(
    readFileSync(join(sdk, "LICENSE"), "utf8"),
    readFileSync(resolve(sdk, "../../../LICENSE"), "utf8"),
  );

  writeFileSync(join(directory, "package.json"), JSON.stringify({ private: true }));
  npm([
    "install", "--offline", "--ignore-scripts", "--no-audit", "--no-fund",
    "--package-lock=false", join(directory, archive.filename),
  ]);
  const installed = join(directory, "node_modules", metadata.name);
  for (const path of resources) {
    assert.deepEqual(readFileSync(join(installed, path)), readFileSync(join(sdk, path)), path);
  }

  const projectName = "typed plugin 插件";
  const generated = join(directory, projectName);
  execFileSync(process.execPath, [
    join(dirname(process.env.npm_execpath), "npx-cli.js"), "--offline", metadata.name, "init", projectName,
  ], {
    cwd: directory,
    env: { ...process.env, npm_config_cache: join(directory, "cache"), npm_config_update_notifier: "false" },
  });
  assert.equal(JSON.parse(readFileSync(join(generated, "manifest.json"), "utf8")).name, projectName);
  const generatedManifestText = readFileSync(join(generated, "manifest.json"), "utf8");
  const generatedManifest = JSON.parse(generatedManifestText);
  assert.equal(generatedManifest.$schema, `https://unpkg.com/flare-plugin-sdk@${metadata.version}/skill/flare-plugin/manifest.schema.json`);
  const validate = new Ajv({ strict: true }).compile(JSON.parse(readFileSync(join(installed, "skill/flare-plugin/manifest.schema.json"), "utf8")));
  assert.equal(validate(generatedManifest), true, JSON.stringify(validate.errors));
  execFileSync(process.execPath, ["--check", join(generated, "plugin.js")]);
  const project = JSON.parse(readFileSync(join(generated, "package.json"), "utf8"));
  for (const tool of ["typescript", "esbuild"]) {
    assert.equal(project.devDependencies[tool], metadata.devDependencies[tool]);
  }
  npm(["install", "--prefer-offline", "--ignore-scripts", "--no-save", "--no-audit", "--no-fund", "--package-lock=false", join(directory, archive.filename)], generated, toolingCache);
  const prebuilt = readFileSync(join(generated, "plugin.js"));
  writeFileSync(join(generated, "src/legacy-types.ts"), `
const legacyConfig: ComposeConfigV1 = { text: { maxLength: 500 } };
function legacyPublish(request: ComposeRequestV1): ComposeResultV1 {
  const target: EntityKeyV1 | undefined = request.replyTo;
  throw new Error(String(target?.id));
}
defineFlareSocialPlugin({ capabilities: { compose: { publish: legacyPublish } } });
function currentPublish(request: ComposeRequestV2): ComposeResultV1 {
  const target: EntityKeyV1 | undefined = request.reference?.key;
  throw new Error(String(target?.id));
}
defineFlareSocialPlugin({ capabilities: { compose: { publish: currentPublish } } });
void legacyConfig;
`);
  npm(["run", "check"], generated);
  npm(["run", "build"], generated);
  assert.deepEqual(readFileSync(join(generated, "plugin.js")), prebuilt, "Rebuild and commit the canonical plugin.js when the TS source changes");
  let registrations = 0;
  vm.runInNewContext(prebuilt.toString("utf8"), {
    defineFlareSocialPlugin(plugin) {
      registrations += 1;
      assert.equal(typeof plugin.login.token.begin, "function");
      assert.equal(typeof plugin.capabilities.timeline.page, "function");
    },
    flare: new Proxy({}, { get() { throw new Error("Host calls during registration"); } }),
  });
  assert.equal(registrations, 1);
  assert.doesNotMatch(prebuilt.toString("utf8"), /\brequire\s*\(|\bmodule\.exports|\bprocess\./);
  mkdirSync(join(generated, "locales"));
  writeFileSync(join(generated, "locales/en.json"), '{"example":"Translation"}\n');
  writeFileSync(join(generated, "locales/README.md"), "Not a locale catalog\n");
  writeFileSync(join(generated, "assets/README.md"), "Not an icon\n");
  npm(["run", "package"], generated);
  const v2Manifest = { ...generatedManifest, apiVersion: 2 };
  assert.equal(validate(v2Manifest), true);
  const generatedPackage = join(generated, "plugin.fpp");
  assert(readFileSync(generatedPackage).length > 0);

  writeFileSync(join(generated, "src/type-check.ts"), `
async function v2Contract(context: PluginInvocationContextV1) {
  const current: CredentialSnapshotV2 = await flare.credential.read({ withRevision: true });
  const response = await flare.http.request({ url: context.origin, redirect: "manual", headers: { "X-Csrf-Token": "token" } });
  const target: string | undefined = response.redirectUrl;
  const replaced: boolean = await flare.credential.replace({ nonce: response.headers["x-nonce"]?.[0] || null }, { expectedRevision: current.revision });
  const cookie: CookieValueV1 = { sourceUrl: context.origin, name: "session", value: "one", attributes: { path: "/", session: true } };
  const pending: LoginTransitionV1 = { type: "pending", pendingPayload: { cookie: { name: cookie.name, value: cookie.value }, target: target || null, replaced } };
  return pending;
}
// @ts-expect-error Unknown redirect mode.
flare.http.request({ url: "https://example.org", redirect: "unsafe" });
// @ts-expect-error Revision must be an opaque string.
flare.credential.replace({}, { expectedRevision: 1 });
const profile: ProfileV1 = { key: { id: "1", host: "example.org" }, handle: "alice", displayName: "Alice" };
const compose: ComposeRequestV2 = { text: "caption", visibility: "public", languages: [], assets: [], sensitive: false, reference: { type: "quote", key: profile.key } };
const composeConfig: ComposeConfigV2 = { supportedTypes: ["new", "reply", "quote"] };
// @ts-expect-error The old replyTo field is not part of compose/v2.
const legacyCompose: ComposeRequestV2 = { ...compose, replyTo: profile.key };
// @ts-expect-error References cannot represent ordinary reposts.
const invalidReference: ComposeRequestV2 = { ...compose, reference: { type: "repost", key: profile.key } };
// @ts-expect-error supportedTypes is required.
const legacyComposeConfig: ComposeConfigV2 = { text: { maxLength: 100 } };
const media: MediaV1 = { id: "image", type: "image", url: "https://cdn.example.org/image", customHeaders: { Referer: "https://example.org/", Cookie: "session=fixture", "X-Media-Token": "fixture" } };
// @ts-expect-error Header values must be strings.
const invalidMedia: MediaV1 = { ...media, customHeaders: { "X-Media-Token": 1 } };
const page: PageV1<TimelineItemV1> = { items: [{ type: "post", post: { key: profile.key, author: profile, createdAt: "2026-01-01T00:00:00Z", content: { value: "Text" } } }] };
defineFlareSocialPlugin({ capabilities: { post: {
  contextTabs(_request): PostContextTabsV2 {
    return { tabs: [{ id: "replies", title: { key: "replies", fallback: "Replies" } }], replyTabId: "replies" };
  },
  contextPage(request) {
    const filter: string | undefined = request.filterId;
    return request.tabId === "replies" && filter === undefined ? page : { endReached: true };
  },
}, manga: {
  detail(request): MangaV1 {
    return { key: request.key, title: "Manga", cover: { ...media, type: "image" }, readingDirection: "rtl", status: "ongoing", chapters: [{ id: "1", title: "Ch 1" }] };
  },
  pages(request): MangaPagesV1 {
    const chapterTarget: NavigationTargetV1 = { type: "mangaChapter", key: request.key, chapterId: request.chapterId };
    void chapterTarget;
    return { pages: [{ id: "p1", media: { id: "m1", type: "image", url: "https://cdn.example.org/1.jpg" } }] };
  },
} } });
// @ts-expect-error MangaPageV1 media accepts only image or gif.
const invalidMangaPage: MangaPageV1 = { id: "p-video", media: { id: "m2", type: "video", url: "https://cdn.example.org/1.mp4" } };
// @ts-expect-error Manga cover accepts only image.
const invalidMangaCover: MangaV1 = { key: profile.key, title: "Manga", cover: { ...media, type: "gif" } };
// @ts-expect-error A manga page image callback can only resolve image or gif.
const invalidMangaPageImage: NonNullable<MangaServiceV1["pageImage"]> = () => ({ ...media, type: "video" });
void [invalidMangaPage, invalidMangaCover, invalidMangaPageImage];
const md5: Promise<string> = flare.crypto.md5("abc");
const extracted: Promise<Record<string, JsonValue>> = flare.html.extract({
  html: "<main><a href='/1'>One</a></main>",
  baseUrl: "https://example.org",
  spec: {
    link: { selector: "a", extract: "attr", name: "href", absoluteUrl: true },
    hasMain: { selector: "main", extract: "exists" },
    items: { selector: "a", all: true, limit: 10, fields: { text: { extract: "ownText" } } },
  },
});
const queried: Promise<Record<string, JsonValue>[]> = flare.html.query({
  html: "<ul><li>Item</li></ul>",
  baseUrl: "https://example.org",
  selector: "li",
  limit: 20,
  fields: { title: { extract: "text" } },
});
const decodedHtml: Promise<string> = flare.html.decodeEntities("1 &amp; 2");
void [extracted, queried, decodedHtml];
// @ts-expect-error Unsupported HTML extract mode.
flare.html.extract({ html: "<p>a</p>", spec: { p: { selector: "p", extract: "markdown" } } });
// @ts-expect-error html.query requires fields.
flare.html.query({ html: "<p>a</p>", selector: "p" });
defineFlareSocialPlugin({
  login: {
    browser: {
      begin: () => ({ type: "webCookie", startUrl: "https://example.org/login" }),
      check(request: CookieCheckRequestV1): LoginTransitionV1 {
        const ua: string | undefined = request.userAgent;
        void ua;
        return { type: "pending", pendingPayload: request.pendingPayload ?? null };
      },
    },
  },
  capabilities: {
    sessionChallenge: {
      complete(request: SessionChallengeCompleteRequestV1): SessionChallengeResultV1 {
        const url: string = request.challengeUrl;
        const ua: string | undefined = request.userAgent;
        const cookies: CookieValueV1[] = request.cookies.cookies;
        const pending: JsonValue | undefined = request.pendingPayload;
        return url && ua && cookies.length >= 0 && pending !== undefined
          ? { type: "pending", pendingPayload: { step: 1 } }
          : { type: "completed" };
      },
    },
    manga: {
      detail(request): MangaV1 {
        return { key: request.key, title: "Manga" };
      },
      pages(request: MangaPagesRequestV1): MangaPagesV1 {
        const offset: number | undefined = request.offset;
        const limit: number | undefined = request.limit;
        const cursor: string | undefined = request.cursor;
        void [offset, limit, cursor];
        return {
          pages: [{ id: "p1", pageToken: "tok-1" }],
          offset: 0,
          totalCount: 10,
          nextCursor: "next-1",
          endReached: false,
        };
      },
      pageImage(request: MangaPageImageRequestV1): MangaPageMediaV1 {
        const attempt: number | undefined = request.attempt;
        const reason: MangaPageImageReasonV1 | undefined = request.reason;
        const failedUrl: string | undefined = request.failedUrl;
        void [attempt, reason, failedUrl];
        return { id: request.pageId, type: "image", url: "https://cdn.example.org/1.jpg" };
      },
    },
  },
});
// @ts-expect-error Invalid SessionChallengeResultV1 type.
const invalidChallengeResult: SessionChallengeResultV1 = { type: "failed" };
// @ts-expect-error Invalid MangaPageImageReasonV1 value.
const invalidImageReason: MangaPageImageReasonV1 = "timeout";
const challengeError: Error = flare.error({
  code: "ChallengeRequired",
  message: { value: "Verify session" },
  challenge: { url: "https://example.org/challenge", seedCookies: [{ name: "s", value: "1" }] },
});
// @ts-expect-error PluginChallengeV1 requires url.
const invalidChallengeError: PluginErrorV1 = { code: "ChallengeRequired", message: { value: "Verify" }, challenge: {} };
void [invalidChallengeResult, invalidImageReason, challengeError, invalidChallengeError];
// @ts-expect-error Unsupported Host method.
flare.fetch("https://example.org");
// @ts-expect-error Invalid SDK error code.
const code: PluginErrorCodeV1 = "Typo";
// @ts-expect-error Browser globals are unavailable.
fetch("https://example.org");
// @ts-expect-error Node globals are unavailable.
process.cwd();
`);
  npm(["run", "check"], generated);
  writeFileSync(join(generated, "src/broken.ts"), 'const broken: number = "invalid";\n');
  assert.throws(() => npm(["run", "build"], generated));
  assert.deepEqual(readFileSync(join(generated, "plugin.js")), prebuilt, "Failed type checking must preserve the working script");
  rmSync(join(generated, "src/broken.ts"));

  const sourcePath = join(generated, "src/plugin.ts");
  const originalSource = readFileSync(sourcePath, "utf8");
  writeFileSync(join(generated, "src/message.ts"), 'export const message = "Bundled module marker";\n');
  writeFileSync(sourcePath, 'import { message } from "./message";\n' + originalSource.replace('"Enter an access token"', "message"));
  npm(["run", "build"], generated);
  assert.match(readFileSync(join(generated, "plugin.js"), "utf8"), /Bundled module marker/);
  const moduleSource = readFileSync(sourcePath, "utf8");
  writeFileSync(sourcePath, 'import { readFile } from "node:fs";\n' + moduleSource.replace("message);", "String(readFile));"));
  assert.throws(() => execFileSync(process.execPath, [join(generated, "build.mjs")], { cwd: generated, stdio: "pipe" }));
  writeFileSync(sourcePath, moduleSource);

  const watcher = spawn(process.execPath, [join(generated, "build.mjs"), "--watch"], { cwd: generated, stdio: ["ignore", "pipe", "pipe"] });
  const closed = once(watcher, "exit");
  let log = "";
  watcher.stdout.on("data", chunk => { log += chunk; });
  watcher.stderr.on("data", chunk => { log += chunk; });
  async function waitFor(check) {
    const deadline = Date.now() + 10000;
    while (!check()) {
      assert.equal(watcher.exitCode, null, log);
      assert(Date.now() < deadline, log);
      await new Promise(resolve => setTimeout(resolve, 25));
    }
  }
  try {
    await waitFor(() => log.includes("watching for changes"));
    writeFileSync(join(generated, "src/message.ts"), 'export const message = "Watch rebuild marker";\n');
    await waitFor(() => readFileSync(join(generated, "plugin.js"), "utf8").includes("Watch rebuild marker"));
    const working = readFileSync(join(generated, "plugin.js"));
    log = "";
    writeFileSync(sourcePath, "defineFlareSocialPlugin({\n");
    await waitFor(() => log.includes("[ERROR]"));
    assert.deepEqual(readFileSync(join(generated, "plugin.js")), working);
    writeFileSync(sourcePath, originalSource);
    await waitFor(() => readFileSync(join(generated, "plugin.js")).equals(prebuilt));
  } finally {
    watcher.kill();
    await closed;
  }

  writeFileSync(join(directory, "plugin.ts"), `
const plugin: PluginDefinitionV1 = {};
defineFlareSocialPlugin(plugin);
const translate: TranslatePluginDefinitionV1 = {
  translateDocumentJson(request, context) { return request.sourceJson + context.pluginId; },
  async translateBatchDocumentJson(request) { return JSON.stringify(request.sourceDocument); },
};
defineFlareTranslatePlugin(translate);
const social: SocialPluginDefinitionV1 = plugin;
defineFlareSocialPlugin(social);
// @ts-expect-error Generic registration has been removed.
defineFlarePlugin(plugin);
// @ts-expect-error Translation is registered separately from social methods.
defineFlareSocialPlugin({ translation: translate });
// @ts-expect-error Both translation handlers are required.
defineFlareTranslatePlugin({ translateDocumentJson: translate.translateDocumentJson });
// @ts-expect-error Social capabilities are not translation methods.
defineFlareTranslatePlugin({ ...translate, capabilities: {} });
const extension: PlatformExtensionPluginDefinitionV1 = {
  actions: { async checkIn(request, context) {
    await flare.account.http.request({ url: "https://m.weibo.cn/api/config" });
    const date: string = request.businessDate;
    const target: string | null | undefined = context.targetPlatformId;
    return { status: "completed", succeeded: date && target ? 1 : 0 };
  } },
};
defineFlarePlatformExtensionPlugin(extension);
defineFlareSocialPlugin({ capabilities: { accountHttp: {
  async authenticate(request, context) {
    const method: string = request.method;
    const body: string | null | undefined = request.bodyBase64;
    const credential = await flare.credential.read();
    return { Authorization: "Bearer " + String(credential), "X-Method": method };
  },
} } });
defineFlareSocialPlugin({ capabilities: { accountCapabilities: {
  async negotiate(request, context) {
    const previous: AccountCapabilitiesV1 = request.previous;
    const credential = await flare.credential.read();
    const origin: string = context.origin;
    return { capabilities: { "flare.datasource.timeline/v1": ["page"] }, composeConfig: previous.composeConfig };
  },
} } });
// @ts-expect-error Account HTTP uses Host authentication.
flare.account.http.request({ url: "https://m.weibo.cn/", cookies: [] });
const host: FlareHostV1 = flare;
void host.crypto.uuid();
// @ts-expect-error Social plugins use the explicit registration entry point.
definePlugin(plugin);
// @ts-expect-error The Host exposes a fixed API, not arbitrary methods.
flare.fetch("https://example.org");
`);
  writeFileSync(join(directory, "tsconfig.json"), JSON.stringify({
    compilerOptions: {
      strict: true,
      noEmit: true,
      target: "ES2022",
      module: "NodeNext",
      moduleResolution: "NodeNext",
      lib: ["ES2022"],
      types: [metadata.name],
    },
    files: ["plugin.ts"],
  }));
  execFileSync(process.execPath, [
    join(sdk, "node_modules/typescript/bin/tsc"), "--project", join(directory, "tsconfig.json"),
  ]);

  const runtime = join(directory, "runtime");
  mkdirSync(runtime);
  for (const path of ["manifest.json", "plugin.js", "assets/icon.png", "locales/en.json"]) {
    mkdirSync(dirname(join(runtime, path)), { recursive: true });
    cpSync(join(generated, path), join(runtime, path));
  }
  const direct = join(directory, "direct.fpp");
  execFileSync(process.execPath, [join(sdk, metadata.bin["flare-plugin-pack"]), runtime, direct]);
  assert.deepEqual(readFileSync(generatedPackage), readFileSync(direct));
  writeFileSync(join(runtime, "manifest.json"), JSON.stringify(v2Manifest));
  const v2Package = join(directory, "v2.fpp");
  execFileSync(process.execPath, [join(sdk, metadata.bin["flare-plugin-pack"]), runtime, v2Package]);
  assert(readFileSync(v2Package).length > 0);

  const processorDir = join(directory, "timeline-processor");
  mkdirSync(join(processorDir, "assets"), { recursive: true });
  cpSync(join(generated, "assets/icon.png"), join(processorDir, "assets/icon.png"));
  writeFileSync(join(processorDir, "manifest.json"), JSON.stringify({
    schemaVersion: 1,
    apiVersion: 2,
    id: "org.example.processor",
    name: "Processor",
    version: "0.1.0",
    minFlareVersion: "0.1.0",
    permissions: {},
    timelineProcessor: { apiVersion: 1 },
  }));
  const processorPackage = join(directory, "processor.fpp");
  assert.throws(
    () => execFileSync(process.execPath, [join(sdk, metadata.bin["flare-plugin-pack"]), processorDir, processorPackage], { stdio: "pipe" }),
    /Background capabilities require plugin\.js/,
  );
  writeFileSync(join(processorDir, "plugin.js"), "defineFlareTimelineProcessorPlugin({ async processBatch() { return { results: [] }; } });\n");
  execFileSync(process.execPath, [join(sdk, metadata.bin["flare-plugin-pack"]), processorDir, processorPackage]);
  assert(readFileSync(processorPackage).length > 0);

  t.diagnostic(`Verified ${archive.files.length} files, offline SDK install, typed project build, stale-output guard, SDK types, module bundling, watch recovery, and packaging`);
});
