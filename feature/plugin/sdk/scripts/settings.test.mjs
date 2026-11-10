import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync, rmSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { build } from "esbuild";
import ts from "typescript";
import test from "node:test";

for (const platform of ["android", "apple"]) {
  test(`settings SDK ${platform} handshake, opaque storage and restricted HTTP`, async t => {
    const calls = [];
    const listeners = new Map();
    const page = { addEventListener: (name, listener) => listeners.set(name, listener) };
    page.top = page;
    const transport = { postMessage(text) {
      const request = JSON.parse(text);
      calls.push(request);
      const value = request.operation === "bridge.hello" ? { version: 1 }
        : request.operation === "storage.read" ? { values: { anything: null }, revision: "opaque" }
        : request.operation === "storage.write" ? { revision: "new" }
        : { status: 200, headers: {}, body: "models", url: request.arguments.url };
      const response = { id: request.id, ok: true, value };
      queueMicrotask(() => platform === "android" ? transport.onmessage({ data: JSON.stringify(response) }) : page.__flareSettingsReceive(response));
    } };
    if (platform === "android") page.__flareNative = transport;
    else page.webkit = { messageHandlers: { flareSettings: transport } };
    globalThis.window = page;
    t.after(() => { delete globalThis.window; });
    const { connectSettings } = await import(`../skill/flare-plugin/settings-sdk.mjs?${platform}`);
    const sdk = await connectSettings();
    assert.equal(await connectSettings(), sdk);
    const store = sdk.storage.open("plugin-owned/name");
    const snapshot = await store.read(["anything"]);
    assert.deepEqual(snapshot, { values: { anything: null }, revision: "opaque" });
    assert.deepEqual(await store.write({ set: { arbitrary: [1, true, null] }, remove: ["old"] }, { expectedRevision: snapshot.revision }), { revision: "new" });
    assert.equal(calls[2].arguments.expectedRevision, "opaque");
    assert.throws(() => store.write({ set: { invalid: Infinity } }), TypeError);
    assert.equal((await sdk.http.request({ url: "https://api.example.org/models" })).body, "models");
    assert.equal(calls.filter(call => call.operation === "bridge.hello").length, 1);
    listeners.get("pagehide")();
    await assert.rejects(store.read(), /closed/);
    assert.equal(sdk.credential, undefined);
    assert.equal(sdk.locale, undefined);
  });
}

test("the public settings export type-checks and bundles for an offline browser page", async t => {
  const root = fileURLToPath(new URL("../", import.meta.url));
  const directory = mkdtempSync(join(root, ".settings-types-"));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const file = join(directory, "page.ts");
  const source = `import {connectSettings, type JsonValue} from "flare-plugin-sdk/settings";
    export async function save(value: JsonValue) {
      const sdk = await connectSettings();
      const store = sdk.storage.open("arbitrary");
      const snapshot = await store.read();
      await store.write({set:{value},remove:["old"]},{expectedRevision:snapshot.revision});
      return sdk.http.request({url:"https://api.example.org/models",redirect:"manual"});
    }`;
  writeFileSync(file, source);
  const program = ts.createProgram([file], { strict: true, noEmit: true, target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.ESNext, moduleResolution: ts.ModuleResolutionKind.Bundler });
  assert.deepEqual(ts.getPreEmitDiagnostics(program).map(d => ts.flattenDiagnosticMessageText(d.messageText, "\n")), []);
  const result = await build({ entryPoints: [file], bundle: true, write: false, format: "esm", platform: "browser", target: "es2020" });
  assert.equal(result.outputFiles.length, 1);
  assert.doesNotMatch(result.outputFiles[0].text, /from ["']flare-plugin-sdk/);
});
