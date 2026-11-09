// Requires Playwright; accepts the same tool overrides as the database browser tests.
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { cp, readFile } from 'node:fs/promises';
import { dirname, isAbsolute, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const moduleName = process.env.FLARE_PLAYWRIGHT_MODULE || 'playwright';
const { chromium } = await import(isAbsolute(moduleName) ? pathToFileURL(moduleName).href : moduleName);
const artifacts = (await readFile(process.argv[2], 'utf8')).trim().split('\n').map(line => JSON.parse(line));
const runner = resolve(dirname(process.env.FLARE_WASM_BINDGEN || 'target/tools/bin/wasm-bindgen'),
    `wasm-bindgen-test-runner${process.platform === 'win32' ? '.exe' : ''}`);
const browser = await chromium.launch({ headless: true, executablePath: process.env.FLARE_CHROMIUM });

try {
    for (const [test, crate] of [['application_web', 'flare-core'], ['web', 'flare-ffi']]) {
        const artifact = artifacts.find(value => value.reason === 'compiler-artifact'
            && value.target.name === test && value.executable?.endsWith('.wasm'));
        assert.ok(artifact, `missing ${crate} browser test artifact`);
        const directory = resolve('crates', crate);
        await cp(resolve('target/database-web'), resolve(directory, 'target/database-web'), { recursive: true });
        const context = await browser.newContext();
        const server = spawn(runner, [artifact.executable, '--nocapture'], {
            cwd: directory,
            env: { ...process.env, NO_HEADLESS: '1', WASM_BINDGEN_TEST_ADDRESS: '127.0.0.1:0' },
            stdio: ['ignore', 'pipe', 'pipe'],
        });
        let output = '';
        let timer;
        try {
            const url = await new Promise((accept, reject) => {
                timer = setTimeout(() => reject(new Error(`test server did not start: ${output}`)), 20000);
                const receive = chunk => {
                    output += chunk;
                    const match = output.match(/http:\/\/127\.0\.0\.1:\d+/);
                    if (match) accept(`${match[0]}/`);
                };
                server.stdout.on('data', receive);
                server.stderr.on('data', receive);
                server.on('error', reject);
                server.on('exit', code => reject(new Error(`test server exited (${code}): ${output}`)));
            });
            clearTimeout(timer);
            const page = await context.newPage();
            await page.goto(url);
            await page.waitForFunction(() => /test result: (ok|FAILED)/.test(document.querySelector('#output')?.textContent), null, { timeout: 45000 });
            const result = await page.locator('#output').textContent();
            assert.match(result, /test result: ok/, result);
            console.log(JSON.stringify({ suite: crate, passed: true, result: result.trim() }));
        } catch (error) {
            console.error(output);
            throw error;
        } finally {
            clearTimeout(timer);
            await context.close();
            server.kill();
        }
    }
} finally {
    await browser.close();
}
