// Real local Keycloak + production images, separate from frontend/e2e REST/OIDC fixtures.
// No traces, password screenshots, token logs, persistent data or external providers.
import { createRequire } from "node:module";
import { readFile, mkdir } from "node:fs/promises";
import { execFileSync } from "node:child_process";
import { basename, isAbsolute } from "node:path";
import assert from "node:assert/strict";
const require = createRequire(new URL("../../frontend/package.json", import.meta.url));
const { chromium } = require("@playwright/test");
const origin = "http://127.0.0.1:18765";
const fixture = process.env.M17_FIXTURE_DIRECTORY;
const output = process.env.M17_EVIDENCE_DIRECTORY ?? "/tmp/traceback-m17-browser";
const indexed = process.env.M17_INDEX_SMOKE === "true";
const chat = process.env.M17_CHAT_SMOKE === "true";
if (!(fixture && isAbsolute(fixture) && basename(fixture).startsWith("traceback-m17-")) || !output.startsWith("/tmp/traceback-m17-"))
  throw new Error("Dedicated private local fixture and evidence directories required");
const login = JSON.parse(await readFile(fixture + "/login.json", "utf8"));
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
const failures = [];
page.on("pageerror", () => failures.push("page-error"));
page.on("console", m => { if (m.type() === "error") failures.push("console-error"); });
let stage = "startup";
async function request(method, path, data) {
  const response = await page.request.fetch(origin + "/api/backend/" + path, {
    method, data, headers: { Origin: origin, "X-Traceback-Request": "1" }, timeout: 180000,
  });
  assert.ok(response.ok(), `BFF ${method} failed: ${response.status()}`);
  return response.status() === 204 ? undefined : response.json();
}
async function poll(path, predicate) {
  const end = Date.now() + 120000;
  do {
    const value = await request("GET", path);
    if (predicate(value)) return value;
    await new Promise(r => setTimeout(r, 1000));
  } while (Date.now() < end);
  throw new Error("Durable processing deadline exceeded");
}
// Small synthetic PDF fixture; no host files/fonts or external PDF generator.
function pdf() {
  const text = "BT /F1 12 Tf 40 700 Td (Synthetic database pool limit is 20.) Tj ET";
  const objects = ["<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
    "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>", `<< /Length ${text.length} >>\nstream\n${text}\nendstream`];
  let data = "%PDF-1.4\n"; const offsets = [0];
  objects.forEach((o, i) => { offsets.push(Buffer.byteLength(data)); data += `${i + 1} 0 obj\n${o}\nendobj\n`; });
  const xref = Buffer.byteLength(data);
  data += `xref\n0 6\n0000000000 65535 f \n${offsets.slice(1).map(v => String(v).padStart(10, "0") + " 00000 n \n").join("")}trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return Buffer.from(data);
}
try {
  stage = "real-keycloak-login";
  await page.goto(origin + "/login");
  assert.match(await page.title(), /Traceback/);
  await page.getByRole("link", { name: "Continue with Keycloak" }).click();
  await page.locator("#username").fill(login.username);
  await page.locator("#password").fill(login.password);
  await page.locator("#kc-login").click();
  await page.waitForURL(url => url.origin === origin && !["/login", "/callback"].includes(url.pathname));
  const createdOrg = await request("POST", "organizations", { name: "M17 synthetic deployment" });
  const org = createdOrg.id;
  await page.goto(origin + `/${org}/overview`);
  await page.getByRole("heading", { name: "Overview", exact: true }).waitFor();
  const otherName = "M17 selection " + crypto.randomUUID();
  const otherOrg = await request("POST", "organizations", { name: otherName });
  await page.reload();
  await page.getByRole("combobox", { name: "Organization" }).click();
  await page.getByRole("option", { name: otherName, exact: true }).click();
  await page.waitForURL(new RegExp(`/${otherOrg.id}/overview$`));
  await page.goto(origin + `/${org}/overview`);
  await page.getByRole("heading", { name: "Overview", exact: true }).waitFor();
  const root = `organizations/${org}`;
  const cookies = await page.context().cookies();
  const session = cookies.find(c => c.name === "traceback_session");
  assert.ok(session?.httpOnly); assert.equal(session.sameSite, "Lax");
  assert.equal((await page.request.get(origin + "/api/session")).status(), 200);
  await page.reload(); await page.getByRole("heading", { name: "Overview", exact: true }).waitFor();
  stage = "incident-crud-and-boundaries";
  const incident = await request("POST", root + "/incidents", { title: "M17 restore smoke", description: "Synthetic only" });
  const changed = await request("PATCH", root + "/incidents/" + incident.id, { version: incident.version, title: "M17 resolved", status: "RESOLVED" });
  assert.equal(changed.version, incident.version + 1);
  assert.equal((await request("GET", root + "/incidents/" + incident.id)).title, "M17 resolved");
  await request("DELETE", root + "/incidents/" + incident.id + "?version=" + changed.version);
  assert.equal((await page.request.post(origin + "/api/backend/organizations", { data: { name: "denied" } })).status(), 403);
  assert.equal((await page.request.get(origin + "/api/backend/actuator/health")).status(), 404);
  assert.equal((await page.request.get("http://127.0.0.1:18080/mcp")).status(), 404);
  stage = "worker-stop-upload-restart";
  // Exact owned disposable service; no developer container can match this name.
  execFileSync("docker", ["stop", "--time", "35", "traceback-m17-worker-1"], { stdio: "ignore" });
  const content = Buffer.from("# Database recovery\nThe synthetic database connection pool limit is 20. Close leaked connections before restarting.\n");
  async function upload(name, media, bytes) {
    const response = await page.request.post(origin + "/api/backend/" + root + "/documents", {
      multipart: { title: name, file: { name, mimeType: media, buffer: bytes } },
      headers: { Origin: origin, "X-Traceback-Request": "1" }, timeout: 60000,
    });
    assert.equal(response.status(), 201, "Document upload failed"); return response.json();
  }
  const document = await upload("recovery.md", "text/markdown", content);
  const revisionPath = root + "/documents/" + document.id + "/revisions/" + document.currentRevision.id;
  assert.equal((await request("GET", revisionPath + "/ingestion")).status, "PENDING");
  execFileSync("docker", ["start", "traceback-m17-worker-1"], { stdio: "ignore" });
  await poll(revisionPath + "/ingestion", value => value.status === "VERIFIED");
  const download = await page.request.get(origin + "/api/backend/" + revisionPath + "/content");
  assert.deepEqual(await download.body(), content);
  stage = "isolated-pdf-upload";
  const parsed = await upload("sandbox.pdf", "application/pdf", pdf());
  await poll(root + "/documents/" + parsed.id + "/revisions/" + parsed.currentRevision.id + "/ingestion", value => value.status === "VERIFIED");
  stage = "retrieval-and-conversations";
  if (indexed) {
    await poll(revisionPath + "/indexing", value => value.status === "INDEXED");
    const result = await request("GET", root + "/search?q=database%20pool%20limit&mode=hybrid");
    assert.equal(result.effectiveMode, "hybrid"); assert.ok(result.results.length > 0);
  } else {
    const result = await request("GET", root + "/search?q=database&mode=keyword");
    assert.equal(result.effectiveMode, "keyword");
  }
  const conversation = await request("POST", root + "/conversations", { title: "M17 synthetic conversation" });
  const conversationPath = root + "/conversations/" + conversation.id;
  assert.equal((await request("GET", conversationPath)).conversation.id, conversation.id);
  if (chat) {
    assert.ok(indexed, "Real chat smoke requires indexed real sources");
    const turn = await request("POST", conversationPath + "/messages", { idempotencyKey: crypto.randomUUID(), content: "What is the database pool limit and what should I do before restarting?" });
    assert.equal(turn.status, "ANSWERED"); assert.ok(turn.citations.length > 0);
    await request("GET", conversationPath + "/messages/" + turn.assistantMessageId + "/citations/" + turn.citations[0].reference);
    await request("DELETE", root + "/documents/" + document.id + "?version=" + document.version);
    assert.equal((await request("GET", conversationPath + "/messages/" + turn.assistantMessageId)).status, "REDACTED");
  }
  stage = "render-and-logout";
  await mkdir(output, { recursive: true });
  await page.screenshot({ path: output + "/overview.png", fullPage: false });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({ path: output + "/mobile.png", fullPage: false });
  assert.deepEqual(await page.evaluate(() => Object.keys(localStorage).filter(k => k !== "traceback:appearance")), []);
  if (process.env.M17_TEST_FRONTEND_RESTART === "true") {
    assert.equal((await page.request.get(origin + "/api/session")).status(), 200);
    await page.goto("about:blank");
    execFileSync("docker", ["restart", "--time", "15", "traceback-m17-frontend-1"], { stdio: "ignore" });
    const deadline = Date.now() + 30000;
    while (true) {
      try { if ((await page.request.get(origin + "/api/health")).ok()) break; } catch {}
      assert.ok(Date.now() < deadline, "Frontend restart deadline");
      await new Promise(r => setTimeout(r, 500));
    }
    assert.equal((await page.request.get(origin + "/api/session")).status(), 401);
    assert.equal((await page.request.get("http://127.0.0.1:18080/actuator/health/readiness")).status(), 200);
  }
  const logout = await page.request.post(origin + "/api/auth/logout", { headers: { Origin: origin, "X-Traceback-Request": "1" } });
  assert.equal(logout.status(), 200);
  assert.equal((await page.request.get(origin + "/api/session")).status(), 401);
  assert.deepEqual(failures, []);
  console.log(JSON.stringify({ passed: true, environment: "disposable local containers + real Keycloak", indexing: indexed, realChat: chat, frontendRestart: process.env.M17_TEST_FRONTEND_RESTART === "true",
    checks: ["PKCE login", "opaque cookie", "session restore", "organization selection", "incident CRUD", "CSRF", "MCP disabled", "upload/download", "worker restart", "PDF sandbox", "search", "conversation persistence", "logout", "console"] }));
} catch {
  // Deliberately suppress exception details that could include OIDC URLs or private values.
  console.error("Deployment smoke failed at stage: " + stage); process.exitCode = 1;
} finally { await browser.close(); }
