import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import vm from "node:vm";

const source = await readFile(new URL("../skill/flare-plugin/examples/platform-extension/plugin.js", import.meta.url), "utf8");

function example(request) {
  let plugin;
  vm.runInNewContext(source, {
    defineFlarePlatformExtensionPlugin: (value) => { plugin = value; },
    flare: {
      account: { http: { request } },
      storage: { open: () => ({ write: async () => {} }) },
    },
  });
  return () => plugin.actions.verifyLogin({ businessDate: "2026-11-10" }, { accountId: "opaque-grant" });
}

test("the platform extension example handles source authentication failures and HTTP login responses", async () => {
  const loginRequired = example(async () => { throw { code: "account.authentication-required" }; });
  assert.equal((await loginRequired()).status, "authentication-required");
  for (const status of [401, 403, 302]) {
    assert.equal((await example(async () => ({ status }))()).status, "authentication-required");
  }
  for (const login of [false, true]) {
    const action = example(async () => ({ status: 200, body: JSON.stringify({ data: { login } }) }));
    assert.equal((await action()).status, login ? "completed" : "authentication-required");
  }
  const failure = new Error("temporary transport failure");
  await assert.rejects(example(async () => { throw failure; }), (error) => error === failure);
});
