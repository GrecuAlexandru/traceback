# ADR-0007 — Isolated extraction and atomic retrieval generations

- **Status:** Accepted for M12 local implementation (user approval 2026-10-14)
- **Date:** 2026-10-14
- **Extends:** ADR-0001, ADR-0003, ADR-0004, ADR-0005, ADR-0006

## Context

M11 verification is bounded byte retrieval and hashing. M12 adds an untrusted PDF parser,
variable-duration local inference and derived searchable artifacts. The existing API
parser and fixed verification lease are unsuitable for this workload. Partial indexes,
stale workers and incompatible equal-dimensional models must never yield visible results.

## Decision

Run Java/PDFBox structural validation and extraction in ephemeral resource-constrained,
network-disabled containers built from the same codebase. Preserve M10 validation errors;
fail closed when isolation cannot launch. Do not grant the parser credentials, host paths
or Docker daemon access. Trusted local API/worker orchestration needs daemon access.

Extend the existing durable outbox/SQS worker architecture with typed indexing events
created atomically after VERIFIED. Add distinct derived generations and renewable token
leases with a total processing deadline. Publish a complete compatible chunk/vector set
and its active pointer in one fenced transaction. Keep a valid previous generation visible
while its replacement is built; failed replacement leaves it intact. Search always checks
current revision, tombstone, published head and current persisted membership inside SQL.

Use Spring AI's compatible Ollama embedding module behind an application-owned provider
interface. Start with local `nomic-embed-text:v1.5`, 768 dimensions and documented task
prefixes. Validate and record the full model digest and configuration. Disable auto-pull;
obtain approval for the local runtime/model download. No external paid provider or chat.
Use PostgreSQL simple full-text search, exact cosine retrieval and RRF. Defer approximate
vector indexes until measured. Preserve historical private artifacts with tombstones.

## Alternatives considered

- A bounded child JVM alone: heap/time limits do not enforce network, native memory or
  filesystem isolation; insufficient as the default PDF boundary.
- A separate extraction service/orchestrator: unnecessary new processing architecture;
  a small isolated parser process integrates with the accepted modular monolith.
- Overwrite chunks in place: readers can see partial or mixed generations and a failed
  reindex can destroy a usable index. An atomic pointer avoids those failure modes.
- Reuse verification's 120-second lease: embedding duration can exceed it; use renewed,
  fenced indexing ownership without changing verification's meaning or lease.
- Global vector top-K followed by filtering: violates tenant authorization before retrieval.
- Automatically download models or send text to hosted APIs: lacks the requested consent.

## Consequences

Docker is a local prerequisite for PDF uploads/extraction. Daemon access remains trusted
orchestration privilege, and container limits are not a VM/kernel exploit guarantee.
Failed dependencies produce durable sanitized failures/retries. Generation history consumes
storage; physical purge is deferred. Model upgrades require new configurations and reindex;
old incompatible vectors cannot enter semantic candidates. Keyword fallback is explicit.
One API writer remains required by M10 recovery; multiple independent workers are supported.

## Implementation status

**CURRENT M12:** V7, isolated parser/validation, provider abstraction/Spring AI Ollama, distinct renewable indexing leases, typed M11 queue events, atomic head publication, tenant-scoped keyword/cosine/RRF API and real synthetic evaluation are implemented locally. Milestone review is pending. See [M12](../M12_EXTRACTION_AND_RETRIEVAL.md) for actual limits, schema, event compatibility, source metadata, security tests and final verification evidence. No RAG answers or MCP were added.
