# ADR-0004 — Durable document ingestion with AWS S3 and SQS

- **Status:** Accepted (target architecture; implementation pending)
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

**PLANNED only.** No S3/SQS worker pipeline in the verified repository baseline.
