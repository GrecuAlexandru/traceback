// Real local PKCE + BFF/API writes. No token extraction, auth changes, or SQL seeding.
import { createRequire } from 'node:module';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { homedir } from 'node:os';
import { createHash } from 'node:crypto';
import assert from 'node:assert/strict';
import { incidents, documents, chats } from './dataset.mjs';
const require = createRequire(new URL('../../frontend/package.json', import.meta.url));
const { chromium } = require('@playwright/test');
const origin = 'http://127.0.0.1:18766'; // Intentionally fixed: cannot seed real or foreign systems.
const directory = homedir() + '/.local/share/traceback/demo';
const verifyOnly = process.argv.includes('--verify-only');
const evidence = '/tmp/traceback-demo-evidence';
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
let stage = 'login';
const failures = [];
page.on('pageerror', () => failures.push('page-error'));
// Seed POST errors are safely handled below; don't capture bodies, URLs or credentials.
function digest(value) { return createHash('sha256').update(value).digest('hex'); }
function key(value) {
  const h = digest('traceback-demo-v1:' + value);
  return `${h.slice(0,8)}-${h.slice(8,12)}-4${h.slice(13,16)}-a${h.slice(17,20)}-${h.slice(20,32)}`;
}
async function raw(method, path, data, multipart) {
  return page.request.fetch(origin + '/api/backend/' + path, { method, data, multipart,
    headers: { Origin: origin, 'X-Traceback-Request': '1' }, timeout: 175000 });
}
async function request(method, path, data) {
  const response = await raw(method, path, data);
  assert.ok(response.ok(), `${method} failed (${response.status()})`);
  return response.status() === 204 ? undefined : response.json();
}
async function all(path) {
  const result = [];
  for (let p = 0; p < 100; p++) {
    const value = await request('GET', `${path}?page=${p}&size=100`);
    const rows = value.items ?? value.content;
    assert.ok(Array.isArray(rows), 'Unsupported page response');
    result.push(...rows);
    if (result.length >= value.totalElements) return result;
  }
  throw new Error('Pagination bound exceeded');
}
async function organization(name) {
  const found = (await request('GET', 'organizations')).filter(o => o.name === name);
  assert.ok(found.length <= 1, 'Ambiguous demo organization; refusing writes');
  if (found[0]) return found[0];
  assert.ok(!verifyOnly, 'Demo organization missing');
  return request('POST', 'organizations', { name });
}
async function seedOrg(org, incidentFixtures, documentFixtures) {
  const root = `organizations/${org.id}`;
  const currentIncidents = await all(root + '/incidents');
  for (const fixture of incidentFixtures) {
    let found = currentIncidents.find(row => row.title === fixture.title);
    if (!found && !verifyOnly) {
      found = await request('POST', root + '/incidents', { title: fixture.title, description: fixture.description });
      if (fixture.status !== 'OPEN') found = await request('PATCH', `${root}/incidents/${found.id}`,
        { version: found.version, status: fixture.status });
    }
    assert.ok(found, 'Expected demo incident missing');
  }
  const currentDocuments = await all(root + '/documents');
  const stored = [];
  for (const fixture of documentFixtures) {
    let found = currentDocuments.find(row => row.title === fixture.title);
    const bytes = fixture.bytes ?? Buffer.from(fixture.text);
    if (!found && !verifyOnly) {
      const response = await raw('POST', root + '/documents', undefined,
        { title: fixture.title, file: { name: fixture.name, mimeType: fixture.media, buffer: bytes } });
      assert.equal(response.status(), 201, 'Upload failed'); found = await response.json();
    }
    assert.ok(found, 'Expected demo document missing');
    // Preserve user edits. Only append the seed's demonstration revision to untouched v1.
    if (fixture.name === 'database-pool-runbook.md' && !verifyOnly) {
      const revisions = await all(`${root}/documents/${found.id}/revisions`);
      if (revisions.length === 1 && found.currentRevision.checksum === digest(bytes)) {
        const response = await raw('POST', `${root}/documents/${found.id}/revisions?version=${found.version}`, undefined,
          { file: { name: fixture.name, mimeType: fixture.media, buffer: Buffer.concat([bytes, Buffer.from('\n## Revision 2 review\nConfirmed pool limit 20 and added a quarterly stale-transaction recovery drill.\n')]) } });
        assert.equal(response.status(), 201, 'Revision upload failed'); found = await response.json();
      }
    }
    const download = await raw('GET', `${root}/documents/${found.id}/content`);
    assert.equal(download.status(), 200, 'Download failed');
    assert.equal(digest(await download.body()), found.currentRevision.checksum, 'Download integrity mismatch');
    const revisions = await all(`${root}/documents/${found.id}/revisions`);
    if (fixture.name === 'database-pool-runbook.md') assert.ok(revisions.length >= 2, 'Revision history missing');
    for (const revision of revisions) {
      const historical = await raw('GET', `${root}/documents/${found.id}/revisions/${revision.id}/content`);
      assert.equal(historical.status(), 200, 'Historical download failed');
      assert.equal(digest(await historical.body()), revision.checksum, 'Historical integrity mismatch');
    }
    stored.push(found);
  }
  return { root, documents: stored };
}
async function waitIndexed(root, docs) {
  const end = Date.now() + 600000;
  let pending = docs;
  while (pending.length) {
    const next = [];
    for (const doc of pending) {
      const path = `${root}/documents/${doc.id}/revisions/${doc.currentRevision.id}`;
      const ingestion = await request('GET', path + '/ingestion');
      assert.ok(!['FAILED', 'CANCELLED'].includes(ingestion.status), 'Ingestion failed');
      const index = await request('GET', path + '/indexing');
      assert.ok(!['FAILED', 'CANCELLED'].includes(index.status), 'Indexing failed');
      if (ingestion.status !== 'VERIFIED' || index.status !== 'INDEXED') next.push(doc);
    }
    if (next.length !== pending.length) console.log(`Indexing: ${docs.length - next.length}/${docs.length} ready`);
    pending = next;
    assert.ok(Date.now() < end, 'Indexing deadline exceeded');
    if (pending.length) await new Promise(r => setTimeout(r, 2000));
  }
}
try {
  const login = JSON.parse(await readFile(directory + '/login.json', 'utf8'));
  await page.goto(origin + '/login');
  await page.getByRole('link', { name: 'Continue with Keycloak' }).click();
  await page.locator('#username').fill(login.username);
  await page.locator('#password').fill(login.password);
  await page.locator('#kc-login').click();
  await page.waitForURL(url => url.origin === origin && !['/login', '/callback'].includes(url.pathname));
  // Password/token values never enter logs or artifacts. Clear password before other work.
  login.password = '';
  stage = 'organizations';
  const main = await organization('Northstar Engineering · Demo');
  const sandbox = await organization('Northstar Sandbox · Demo');
  stage = 'main-fixtures';
  const seeded = await seedOrg(main, incidents, documents);
  stage = 'sandbox-fixtures';
  const sandboxIncidents = incidents.slice(0, 4).map(i => ({ ...i, title: 'Sandbox: ' + i.title }));
  const sandboxDocuments = documents.slice(0, 2).map(d => ({ ...d, title: 'Sandbox: ' + d.title }));
  const isolated = await seedOrg(sandbox, sandboxIncidents, sandboxDocuments);
  console.log('Demo corpus present: 28 incidents, 19 documents, two organizations');
  stage = 'indexing';
  await waitIndexed(seeded.root, seeded.documents);
  await waitIndexed(isolated.root, isolated.documents);
  stage = 'search-and-boundaries';
  for (const mode of ['keyword', 'semantic', 'hybrid']) {
    const result = await request('GET', seeded.root + `/search?q=database%20connection%20pool%20limit&mode=${mode}`);
    assert.equal(result.effectiveMode, mode, 'Requested search mode not available');
    assert.ok(result.results.length > 0, 'Search returned no evidence');
    assert.ok(result.results.every(r => !isolated.documents.some(d => d.id === r.documentId)), 'Foreign search result');
  }
  assert.equal((await raw('GET', `${seeded.root}/documents/${isolated.documents[0].id}`)).status(), 404);
  const foreignIncidents = await all(isolated.root + '/incidents');
  assert.equal((await raw('GET', `${seeded.root}/incidents/${foreignIncidents[0].id}`)).status(), 404);
  assert.equal((await page.request.post(origin + '/api/backend/organizations', { data: { name: 'CSRF denied' } })).status(), 403);
  stage = 'conversations';
  const currentChats = await all(seeded.root + '/conversations');
  for (const fixture of chats) {
    let conversation = currentChats.find(c => c.title === fixture.title);
    if (!conversation && !verifyOnly) conversation = await request('POST', seeded.root + '/conversations', { title: fixture.title });
    assert.ok(conversation, 'Expected demo conversation missing');
    const path = `${seeded.root}/conversations/${conversation.id}`;
    let history = await request('GET', path);
    if (!history.messages.length && !verifyOnly) {
      console.log(`Generating cited demo conversation: ${fixture.title}`);
      const turn = await request('POST', path + '/messages', { idempotencyKey: key(fixture.title), content: fixture.question });
      assert.equal(turn.status, 'ANSWERED', 'Real demo answer unavailable; no new-key retry performed');
      assert.ok(turn.citations.length > 0, 'Answer lacks real citations');
      history = await request('GET', path);
    }
    const answers = history.messages.filter(m => m.role === 'ASSISTANT' && m.status === 'ANSWERED');
    assert.ok(answers.length > 0, 'Persisted answer missing');
    for (const answer of answers) {
      assert.ok(answer.citations.length > 0);
      for (const citation of answer.citations)
        await request('GET', `${path}/messages/${answer.id}/citations/${citation.reference}`);
    }
  }
  stage = 'render';
  await mkdir(evidence, { recursive: true });
  for (const route of ['overview', 'incidents', 'documents', 'assistant']) {
    await page.goto(`${origin}/${main.id}/${route}`);
    await page.getByRole('heading', { name: { overview: 'Overview', incidents: 'Incidents', documents: 'Knowledge Base', assistant: 'AI Assistant' }[route], exact: true }).waitFor();
    const loaded = { overview: 'Inventory: recovery drill and regression follow-up',
      incidents: 'Inventory: recovery drill and regression follow-up',
      documents: 'Incident command quick reference (PDF)', assistant: 'Notification backlog triage' }[route];
    await page.getByText(loaded, { exact: true }).first().waitFor();
    if (route === 'overview') await page.getByText('Incident command quick reference (PDF)', { exact: true }).waitFor();
    if (route === 'assistant') {
      await page.getByRole('link', { name: chats[0].title, exact: true }).click();
      await page.getByText(chats[0].question, { exact: true }).waitFor();
    }
    await page.screenshot({ path: `${evidence}/${route}.png` });
  }
  assert.deepEqual(failures, []);
  const summary = { verifiedAt: new Date().toISOString(), origin, organizationId: main.id,
    organizations: 2, incidents: 28, documents: 19, conversations: 3,
    checks: ['real PKCE login', 'authorized writes', 'checksummed downloads', 'revision history', 'all documents indexed',
      'keyword/semantic/hybrid search', 'foreign-ID denial', 'CSRF denial', 'persistent cited conversations', 'rendered screens'] };
  if (!verifyOnly) await writeFile(directory + '/seed-summary.json', JSON.stringify(summary, null, 2), { mode: 0o600 });
  console.log(JSON.stringify(summary));
} catch (error) {
  console.error('Demo failed at stage: ' + stage);
  // Only assertion messages under our control; never print browser/HTTP errors or URLs.
  if (error?.code === 'ERR_ASSERTION') console.error(error.message.split('\n')[0]);
  process.exitCode = 1;
} finally { await browser.close(); }
