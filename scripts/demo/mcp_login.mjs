// Synthetic browser interaction only. The Python parent owns the real loopback callback.
import { createRequire } from 'node:module';
import { readFile } from 'node:fs/promises';
import { homedir } from 'node:os';
const require = createRequire(new URL('../../frontend/package.json', import.meta.url));
const { chromium } = require('@playwright/test');
let input = ''; for await (const chunk of process.stdin) input += chunk;
const {url, callback} = JSON.parse(input);
const target = new URL(url);
if (target.origin !== 'http://127.0.0.1:18181' || target.pathname !== '/realms/traceback-demo/protocol/openid-connect/auth' || callback !== 'http://127.0.0.1:8767/callback') process.exit(1);
const browser = await chromium.launch();
try {
 const page = await browser.newPage();
 const login = JSON.parse(await readFile(homedir() + '/.local/share/traceback/demo/login.json', 'utf8'));
 await page.goto(url);
 await page.locator('#username').fill(login.username); await page.locator('#password').fill(login.password);
 await page.locator('#kc-login').click();
 await page.waitForURL(u => u.origin === new URL(callback).origin && u.pathname === '/callback');
} catch { process.exitCode = 1; }
finally { await browser.close(); }
