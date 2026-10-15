// Fictional operational material only. No real customer data, credentials or endpoints.
export const scenarios = [
  ['database-pool', 'Database connection pool saturation', 'Database', 'SEV-2', 'OPEN',
    'Checkout p95 rose from 180 ms to 2.4 s; 7% of requests timed out while the pool stayed at 20/20 active connections.',
    'A reporting query held transactions open during a traffic burst. Idle-in-transaction sessions prevented pool recovery.',
    'Pause reporting exports, cancel only the identified stale reporting sessions, and verify connections return to the pool. Do not raise the pool limit before checking the database connection budget.',
    'Keep the application pool limit at 20, connection acquisition timeout at 3 seconds, and reporting statement timeout at 15 seconds. Page on pool utilization above 90% for five minutes.',
    'What is the database connection pool limit and what should we do before increasing it?'],
  ['payment-timeouts', 'Payment provider timeout cascade', 'Payments', 'SEV-1', 'INVESTIGATING',
    'Payment authorization errors reached 12%; completed orders were delayed but no confirmed duplicate charges were observed.',
    'A slow upstream exhausted outbound connections. Unbounded retries amplified load and obscured the original timeout.',
    'Open the circuit breaker after five consecutive failures for 30 seconds. Limit payment attempts to two with jitter and preserve the original payment idempotency key.',
    'Use a 2 second connect timeout and 4 second response timeout. Reconcile uncertain outcomes with the provider before retrying a charge; never generate a new idempotency key for the same payment.',
    'How should we handle payment timeouts without charging a customer twice?'],
  ['queue-backlog', 'Notification queue backlog after campaign', 'Messaging', 'SEV-2', 'OPEN',
    'The oldest notification reached 18 minutes and the backlog grew to 42,000 messages after a campaign.',
    'The email provider throttled requests while workers retried immediately, consuming capacity without draining the queue.',
    'Pause low-priority campaigns, apply exponential backoff with jitter, and check provider limits before adding workers. Keep transactional messages ahead of campaigns.',
    'Alert when oldest message age exceeds 120 seconds for ten minutes. Cap consumers at eight until downstream quotas are confirmed. Redrive only reviewed dead-letter messages.',
    'How do we safely drain a notification queue backlog?'],
  ['oidc-clock', 'Authentication failures caused by clock drift', 'Identity', 'SEV-2', 'RESOLVED',
    'Valid sign-ins were followed by intermittent 401 responses on one API node; other nodes accepted the same login.',
    'A host clock drifted 95 seconds after time synchronization stopped, violating token not-before validation.',
    'Drain the affected node, restore time synchronization, and compare issuer discovery and JWKS health before returning the node to service.',
    'Validate signature, issuer, audience and expiry on every request. Keep clock offset below one second. Never disable token validation or widen clock tolerance to conceal drift.',
    'What should we check when valid logins intermittently receive 401 responses?'],
  ['deployment-regression', 'Release regression in order serialization', 'Orders', 'SEV-1', 'RESOLVED',
    'A new release returned 500 responses for 9% of order detail requests with an older optional address format.',
    'The serializer assumed a nested address was always present. Contract fixtures covered only newly created orders.',
    'Stop the release, drain and stop the new application, then restore the last compatible image. Keep the additive database migration; do not roll back stored customer data.',
    'Use stop-before-start releases for the singleton API. Gate deployment on old-record contract fixtures and readiness checks. A rollback must not overlap two API writers.',
    'How do we roll back an incompatible application release safely?'],
  ['cache-stampede', 'Catalog cache stampede at TTL boundary', 'Catalog', 'SEV-2', 'INVESTIGATING',
    'Catalog read traffic to PostgreSQL increased sixfold when a popular category cache expired; p99 reached 3 seconds.',
    'Identical cache entries expired together and every request started a database refresh.',
    'Enable per-key request coalescing and serve the last valid category value for up to 60 seconds during refresh. Avoid flushing the entire cache.',
    'Set a five-minute base TTL with up to 20% jitter. Permit one refresh per key. Alert on cache hit rate below 85% paired with rising database reads.',
    'How do we prevent a cache stampede when catalog entries expire?'],
  ['webhook-signatures', 'Webhook signature verification failures', 'Integrations', 'SEV-2', 'RESOLVED',
    'Partner webhook rejection rose to 35% immediately after a signing-key rotation; rejected deliveries remained queued upstream.',
    'The verifier switched keys before the partner completed its rotation. A middleware change also risked normalizing raw payload bytes.',
    'Restore the approved overlapping verification keys for 24 hours and verify signatures against the original raw body. Request redelivery after successful validation.',
    'Reject timestamps outside five minutes and deduplicate accepted event IDs for seven days. Never accept an unsigned webhook or log signing material.',
    'How should webhook signing-key rotation and replay protection work?'],
  ['disk-pressure', 'Application log volume filled local disk', 'Runtime', 'SEV-2', 'OPEN',
    'A node crossed 95% disk utilization and uploads failed with insufficient space while health probes still returned 200.',
    'Debug logging remained enabled after an investigation and rotation kept too many uncompressed files.',
    'Turn off debug logging, rotate only owned application logs, and confirm free space before restoring uploads. Preserve incident evidence and database volumes.',
    'Keep at least 20% free disk capacity. Rotate logs daily or at 100 MB, retain seven compressed files, and alert at 80% utilization.',
    'What is the response procedure for disk pressure caused by application logs?'],
  ['dns-expiry', 'Internal DNS lookup failures during resolver change', 'Networking', 'SEV-2', 'INVESTIGATING',
    'Workers could not resolve a downstream hostname for six minutes; queued work accumulated without data loss.',
    'A resolver change removed a forwarding rule before replacement configuration propagated.',
    'Restore the last known forwarding rule and verify resolution from the affected application network. Let normal retries drain the queue once DNS is healthy.',
    'Validate A and AAAA records, resolver reachability and TTL behavior. Use a 60 second TTL during planned cutovers; never hardcode service IPs as a permanent repair.',
    'What should we verify during an internal DNS outage?'],
  ['tenant-filter', 'Tenant-scoped search regression caught before release', 'Search', 'SEV-2', 'RESOLVED',
    'A pre-release security fixture detected that a refactored candidate query could rank documents before applying tenant filters. The release was blocked.',
    'A query helper moved membership checks outside the SQL candidate selection. Synthetic foreign documents exposed the regression.',
    'Keep the release blocked and restore organization and membership predicates inside retrieval SQL before ranking and limits. Re-run known foreign-ID and membership-revocation tests.',
    'Authorize before retrieval and again before model context assembly. Citations resolve through current source access; cached excerpts never confer permission.',
    'Where must tenant authorization be enforced for retrieval and AI answers?'],
  ['indexing-recovery', 'Document indexing stalled after worker restart', 'Knowledge', 'SEV-3', 'OPEN',
    'Newly uploaded runbooks were downloadable but absent from search after a worker terminated during embedding.',
    'The verification job completed, but the index generation still held a lease until its recovery deadline.',
    'Check verification and indexing separately. Restore the worker and embedding runtime, then allow the durable lease and outbox recovery to resume the existing generation.',
    'VERIFIED means checksum and byte validation only. INDEXED means a complete generation was atomically published. Never publish partial chunks or rewrite immutable source metadata.',
    'Why can a verified uploaded document still be missing from search?'],
  ['rate-limit', 'Partner API rate limiting during inventory sync', 'Inventory', 'SEV-3', 'INVESTIGATING',
    'Inventory sync lagged by 25 minutes; partner responses returned 429 while customer reads continued using the last valid stock snapshot.',
    'Parallel sync jobs exceeded a shared partner quota and ignored Retry-After headers.',
    'Reduce concurrency to two, honor Retry-After, and resume from the last durable cursor. Keep the most recent valid snapshot visible with its freshness timestamp.',
    'Use a shared rate budget of 60 requests per minute and checkpoint every successful page. Do not reset the cursor or replay the entire catalog automatically.',
    'How should inventory synchronization respond to partner rate limits?'],
];

export const incidents = scenarios.flatMap(([slug, title, service, severity, status, impact, cause, mitigation, prevention], i) => [
  { title, status, description: `Synthetic demo incident DEMO-${String(i * 2 + 1).padStart(3, '0')}\nService: ${service}\nSeverity: ${severity}\n\nImpact\n${impact}\n\nInvestigation\n${cause}\n\nResponse\n${mitigation}\n\nFollow-up\n${prevention}\n\nOwner: ${service} on-call. All systems and events in this record are fictional.` },
  { title: `${service}: recovery drill and regression follow-up`, status: 'RESOLVED', description: `Synthetic recovery drill DEMO-${String(i * 2 + 2).padStart(3, '0')}\nService: ${service}\nSeverity: SEV-3\n\nScenario\nReplay the ${slug} failure against isolated test fixtures.\n\nTimeline\n09:00 UTC: triggered the synthetic fault.\n09:04 UTC: alert acknowledged and impact assessed.\n09:12 UTC: applied the documented mitigation.\n09:25 UTC: recovery verified and drill closed.\n\nVerified response\n${mitigation}\n\nRegression acceptance\n${prevention}\n\nOutcome\nThe exercise passed with synthetic traffic. Record a new incident if the fault recurs; do not treat this drill as evidence about a real production system.` },
]);

export const documents = scenarios.map(([slug, title, service, severity, status, impact, cause, mitigation, prevention, question]) => ({
  title: `${service} runbook: ${title.toLowerCase()}`, name: `${slug}-runbook.md`, media: 'text/markdown',
  text: `# ${title}\n\nSynthetic Northstar Engineering runbook. Service owner: ${service} on-call.\n\n## Symptoms and impact\n${impact}\n\n## Likely failure mode\n${cause}\n\n## Immediate response\n${mitigation}\n\n## Operating limits and prevention\n${prevention}\n\n## Recovery verification\nCompare error rate, latency and saturation to the pre-incident baseline for 15 minutes. Confirm queued work drains, retries remain bounded, and data integrity checks pass. Escalate to the incident commander if impact grows.\n\n## Investigation question\n${question}\n\n## Safety\nThis is fictional training material. Preserve tenant authorization and immutable evidence. Do not include credentials, personal data or production payloads in incident notes.\n`,
}));

for (const slug of ['payment-timeouts', 'deployment-regression', 'cache-stampede']) {
  const s = scenarios.find(s => s[0] === slug);
  documents.push({ title: `Postmortem: ${s[1]}`, name: `${slug}-postmortem.md`, media: 'text/markdown',
    text: `# Postmortem: ${s[1]}\n\nFictional incident; blameless engineering review.\n\n## Summary\n${s[5]}\n\n## Timeline (synthetic UTC)\n10:05: alert fired.\n10:08: incident declared and on-call engaged.\n10:16: failure mode isolated.\n10:23: mitigation applied.\n10:40: metrics stabilized.\n\n## Contributing factors\n${s[6]}\n\n## Resolution\n${s[7]}\n\n## Corrective actions\n${s[8]}\nOwner: ${s[3]} incident commander and ${s[2]} service team. Track the preventive work in the next reliability review.\n\n## What went well\nThe team preserved evidence, communicated impact, and used bounded recovery steps.\n\n## Detection improvement\nAdd a regression fixture for the exact fault and compare saturation with customer-facing error rates.\n` });
}
documents.push({ title: 'On-call handover and escalation checklist', name: 'on-call-handover.txt', media: 'text/plain',
  text: `NORTHSTAR ENGINEERING - SYNTHETIC ON-CALL HANDOVER\n\nSEV-1: broad outage or payment correctness risk; engage the incident commander immediately.\nSEV-2: significant degradation; acknowledge within five minutes.\nSEV-3: limited impact or recovery exercise; triage during the current shift.\n\nAt shift start: check open incidents, queue age, indexing status, recent releases and database saturation.\nDuring an incident: record UTC timestamps, affected services, observed impact and evidence.\nUpdate the internal status note every 15 minutes during active SEV-1/SEV-2 incidents.\nBefore handover: identify an owner, next decision, rollback option and recovery criteria for every active incident.\nNever copy tokens, passwords, customer payloads or private source excerpts into public updates.\nAll content is fictional training data.\n` });

// Generate a valid single-page PDF without extra packages, remote fonts or assets.
export function makePdf(lines) {
  const escaped = lines.map(line => line.replaceAll('\\', '\\\\').replaceAll('(', '\\(').replaceAll(')', '\\)'));
  const stream = `BT /F1 12 Tf 40 740 Td 16 TL ${escaped.map((line, i) => `${i ? 'T* ' : ''}(${line}) Tj`).join('\n')} ET`;
  const objects = ['<< /Type /Catalog /Pages 2 0 R >>', '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>', `<< /Length ${Buffer.byteLength(stream)} >>\nstream\n${stream}\nendstream`];
  let text = '%PDF-1.4\n'; const offsets = [];
  objects.forEach((object, i) => { offsets.push(Buffer.byteLength(text)); text += `${i + 1} 0 obj\n${object}\nendobj\n`; });
  const xref = Buffer.byteLength(text);
  text += `xref\n0 6\n0000000000 65535 f \n${offsets.map(n => `${String(n).padStart(10, '0')} 00000 n \n`).join('')}trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return Buffer.from(text);
}
documents.push({ title: 'Incident command quick reference (PDF)', name: 'incident-command.pdf', media: 'application/pdf',
  bytes: makePdf(['Northstar Engineering - Incident Command', 'Synthetic training reference', '',
    '1. Declare severity, commander and affected service.', '2. Record UTC timestamps and observed customer impact.',
    '3. Prefer reversible, bounded mitigation steps.', '4. Reuse payment idempotency keys for uncertain outcomes.',
    '5. Keep the database connection pool limit at 20.', '6. Update active incident notes every 15 minutes.',
    '7. Verify recovery for 15 minutes before resolving.', '8. Preserve evidence and assign preventive actions.']) });

export const chats = [
  { title: 'Database pool investigation', question: scenarios[0][9] },
  { title: 'Safe payment recovery', question: scenarios[1][9] },
  { title: 'Notification backlog triage', question: scenarios[2][9] },
];
