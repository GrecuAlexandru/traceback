import test from 'node:test';
import assert from 'node:assert/strict';
import { incidents, documents, chats, makePdf } from './dataset.mjs';

test('corpus covers all statuses with unique, valid bounded API inputs', () => {
  assert.equal(incidents.length, 24);
  assert.deepEqual(new Set(incidents.map(i => i.status)), new Set(['OPEN', 'INVESTIGATING', 'RESOLVED']));
  assert.equal(new Set(incidents.map(i => i.title)).size, incidents.length);
  for (const i of incidents) {
    assert.ok(i.title.length <= 200);
    assert.ok(i.description.length > 400 && i.description.length <= 20000);
    assert.match(i.description, /[Ss]ynthetic/);
  }
});
test('search corpus has real Markdown, text and PDF bytes without credential material', () => {
  assert.equal(documents.length, 17);
  assert.equal(new Set(documents.map(d => d.title)).size, documents.length);
  assert.deepEqual(new Set(documents.map(d => d.media)), new Set(['text/markdown', 'text/plain', 'application/pdf']));
  for (const d of documents) {
    const data = d.bytes ?? Buffer.from(d.text);
    assert.ok(data.length > 200 && data.length < 10485760);
    assert.doesNotMatch(data.toString(), /eyJ[a-zA-Z0-9_-]{20}|sk-proj-|BEGIN PRIVATE KEY/);
  }
  assert.equal(chats.length, 3);
  assert.ok(chats.every(c => c.question.length < 1000 && c.title.length < 120));
});
test('generated PDF points startxref and object offsets at valid byte boundaries', () => {
  const pdf = makePdf(['Synthetic (safe) PDF', 'Escape \\ correctly']).toString();
  const xref = Number(pdf.match(/startxref\n(\d+)/)[1]);
  assert.equal(pdf.slice(xref, xref + 4), 'xref');
  for (const [i, offset] of [...pdf.matchAll(/^(\d{10}) 00000 n /gm)].entries())
    assert.ok(pdf.slice(Number(offset[1])).startsWith(`${i + 1} 0 obj`));
});
