# ADR-0006 — Ingestion leases and explicit storage compatibility

- **Status:** Accepted for M11 implementation; M11 approved for local development 2026-10-14
- **Date:** 2026-10-14
- **Extends:** ADR-0001, ADR-0003, ADR-0004

## Context

M10 has immutable revisions, two-phase upload publication and single-process filesystem/
database locking. Its startup recovery cannot safely run in a second uploading process.
M11 must permit independent preparation workers and S3 without reinterpreting existing
LOCAL UUID keys. PostgreSQL/S3/SQS cannot commit atomically together.

## Decision

Keep one API writer/recovery owner per database. Workers skip upload recovery, deny
application HTTP and open legacy POSIX storage read-only. Persist an immutable LOCAL/S3
provider on each revision; old content remains LOCAL without automatic migration/backfill.
Keep the bounded authorized multipart API, extending its storage adapter with conditional
SHA-256 S3 writes instead of adding a second presigned-upload lifecycle.

Publish a preparation job and outbox event in the AVAILABLE publication transaction.
Dispatch outside PostgreSQL using SKIP LOCKED claims and token leases. Assume duplicate
publication after a send-before-delivery-commit crash. Workers match every opaque message
reference against a persisted event, lock document before job, then retrieve/hash outside
the transaction. Completion rechecks revision/tombstone, generation/token and lease before
commit; SQS acknowledgement follows durable handling. No JVM lock governs distributed jobs.

Cancelled/verified duplicates are durable no-ops. Permanent/exhausted failures remain
FAILED and unacknowledged for DLQ redrive; bounded transient retries preserve attempts.
Automatic reconciliation rearms stale delivered events without resetting live/terminal
jobs. An explicit database-administration retry creates a new generation/event, making
old deliveries obsolete. VERIFIED means only bytes/size/SHA-256 preparation, never indexing.

## Alternatives considered

- Replace M10 locks with multi-writer upload leases now: larger upload/recovery redesign
  unnecessary for independently executable read-only preparation workers.
- Reinterpret all keys through the configured provider: breaks existing files and creates
  ambiguous metadata; rejected in favor of per-revision provider ownership.
- Direct commit plus queue send: loses events during outages; rejected for a DB outbox.
- Exactly-once cross-system commit: unavailable; use unique jobs and fenced idempotency.
- Hold DB locks during network processing: blocks deletion/supersession and consumes long
  transactions; bounded reads use an authorization snapshot, with fenced finalization.
- Add parsing/chunking now: explicitly outside approved M11; requires isolation/long-work
  lease renewal and a future extraction state machine.

## Consequences

Multiple workers and dispatchers are supported; multiple API writers remain unsupported.
Legacy workers need the same private read-only filesystem. In-flight authorized bytes
cannot be retracted by a tombstone, but no active result can commit afterward. Existing
source data and tombstones are retained; purge/migration require a separate reviewed plan.
Longer extraction work must introduce renewal/isolation before using these bounds.
LocalStack is local and ephemeral; tests establish protocol/state behavior, not real AWS
IAM/durability/performance. Real cloud configuration/deployment remains separately approved.

## Implementation status

V6, routing/S3 adapters, transactional jobs/outbox, leased dispatch/worker verification,
retry/reconciliation/DLQ and status/health/metrics are implemented. Disposable PostgreSQL/
LocalStack integration tests and a separate scheduled worker process exercise the design.
See [M11](../M11_DURABLE_INGESTION.md) for exact final verification and commands.


**CURRENT M12 extension:** ADR-0007 was explicitly approved on 2026-10-14. Verification retains its original lease/meaning; separate indexing uses 90-second renewable token ownership, 30-second DB/SQS heartbeat and ten-minute per-claim deadline. Original revision/provider immutability, read-only legacy worker access and one API writer remain unchanged. See [M12](../M12_EXTRACTION_AND_RETRIEVAL.md).

**M17 packaging:** The single API writer/recovery restriction remains deliberate and is
checked by database ownership, readiness and a second S3-only container refusal test.
Independent worker leases are preserved. Optional S3-only startup rejects any LOCAL history;
no immutable provider is rewritten. See [ADR-0011](0011-production-packaging-and-explicit-deployment-blockers.md).
