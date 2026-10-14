# ADR-0004 — Durable document ingestion with AWS S3 and SQS

- **Status:** Accepted (M11 local implementation; cloud deployment pending)
- **Date:** 2026-10-13

## Context

Parsing, chunking, and embedding documents can be slow and may fail independently of an HTTP upload request. We also want to learn meaningful AWS integration without building a general-purpose job orchestration platform.

## Proposed decision

Store original documents privately in S3. Persist document metadata, processing state, and an outbox record transactionally in PostgreSQL. Publish outbox events to SQS and let Java workers process them. Use idempotency keys, retries with backoff, visibility-timeout management, and a dead-letter queue. Ensure document/index state never indicates success after partial processing.

## Alternatives considered

- **Synchronous parsing in an HTTP request:** timeouts and unreliable user experience.
- **Direct database write plus direct queue publish:** potential inconsistency if only one operation succeeds.
- **Kafka/Kubernetes:** unnecessary scope for this stage.
- **Database-backed queue only:** viable initial local implementation; real SQS still useful as an explicit cloud learning milestone.

## Consequences

- Must test redelivery, worker crashes, partial writes, and cleanup.
- Requires IAM least privilege, storage limits, secrets handling, and careful AWS cost control.
- AWS creation/deployment needs explicit user approval.

## Implementation status

The following M10 precursor note records the earlier baseline; M11 implementation is described below.


**CURRENT M10 precursor:** Original files are stored privately by a local POSIX adapter with pending/available/failed metadata and startup reconciliation. A storage interface provides the later S3 extension point. This does not implement the S3/SQS/outbox ingestion target, text extraction or a distributed worker. See [M10](../M10_DOCUMENT_MANAGEMENT.md).

**CURRENT M11:** S3/SQS-compatible storage/queue adapters run against local-only LocalStack, with per-revision provider compatibility, transactional outbox, independent verification workers, token/generation leases, retries, DLQ and reconciliation. Processing verifies bytes/size/SHA-256 only; parsing/chunking/embeddings are future work. API uploads remain bounded multipart, without presigned URLs or automatic LOCAL migration. See [ADR-0006](0006-ingestion-leases-and-storage-compatibility.md) and [M11 lifecycle/evidence](../M11_DURABLE_INGESTION.md).
