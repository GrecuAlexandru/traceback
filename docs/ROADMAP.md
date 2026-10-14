# Traceback — Development Roadmap

**Last source review:** Local `codex/m13-rag-chat`, 2026-10-14; M9A–M13 approved for local development by the M14 request; M14 verified locally (221 tests); review pending.
**Execution rule:** Work on **one milestone per agent task**. Do not start the next milestone until the user accepts the previous one.

## Status definitions

- **Code present:** implementation found in GitHub; runtime/tests may still require verification.
- **Implemented locally; review pending:** Local code and verification exist; user acceptance is still required.
- **Next:** the next milestone to implement.
- **Planned:** not implemented or not verified as complete.
- **Accepted:** tests, demonstration, security checks, and user review completed; only mark after evidence is available.

## Baseline — code present

- Java 21 target / Spring Boot 4.1.1 / Maven Wrapper.
- Incident CRUD, filtering/pagination, optimistic locking and `409 Conflict` handling.
- PostgreSQL and Flyway V1/V2.
- JWT resource-server configuration and authenticated identity endpoint.
- Original Testcontainers/MockMvc baseline: 11 tests passed during M9A preflight; final suite: 27 passing tests. See [M9A verification](M9A_ORGANIZATIONS.md#verification).

**M9A preflight completed:** The original 11 tests passed. Local Compose already includes PostgreSQL and Keycloak on loopback, OIDC discovery matches the configured issuer, and Flyway already uses `classpath:db/migration`. V3 already exists and remains immutable. Root secret ignore rules now also cover nested/variant `.env` files.

## M9A — Users, organizations, memberships — APPROVED for local development

**Deliver:**

- Flyway V3 for application users (issuer + subject), organizations, and membership roles.
- `ADMIN`, `ENGINEER`, `VIEWER` membership model with uniqueness and foreign keys.
- Authenticated organization-create and list-my-organizations endpoints.
- Atomic creation of organization plus its initial administrator membership.
- Explicit service-account/organization-bootstrap policy; no arbitrary privilege escalation by generic machine clients.
- Testcontainers tests for creation, same-user listing, different-user isolation, invalid input, and concurrent first-time identity creation (or an explicitly recorded follow-up).

**Acceptance:** Users can only list their memberships; failures leave no orphaned organizations; existing incident rows and prior migrations are untouched; all relevant tests pass. **Do not** claim incident tenant isolation yet.

**Implemented:** Application identity and membership entities, authenticated `POST`/`GET /api/v1/organizations`, atomic administrator bootstrap, a fail-closed trusted-human claim policy, concurrency-safe identity insertion, and PostgreSQL tests for isolation, roles, validation, constraints, forced rollback, concurrent first use, and V2-to-V3 data preservation. No migration was edited or added because V3 already supplies the required schema. See [M9A_ORGANIZATIONS.md](M9A_ORGANIZATIONS.md) for API details and verification evidence. Approved by the user for local development. The separate [local Keycloak follow-up](LOCAL_KEYCLOAK_SMOKE.md) now verifies real human/service-account tokens and dedicated mappers; this is not production identity-provider/deployment verification.

## M9B — Tenant-scoped incidents and RBAC — APPROVED for local development

**Deliver:**

- Versioned migration adding organization ownership to incidents without exposing legacy rows. Produce and review a safe plan for existing records before making the column mandatory.
- Organization-scoped incident CRUD/list/pagination; server-side membership and role checks on every path.
- `ADMIN`/`ENGINEER` write permissions; `VIEWER` read only; consistent missing/foreign-ID handling.
- Decommission or strictly secure legacy global `/api/v1/incidents` routes; never leave an unscoped bypass.
- Cross-tenant, role, guessing-UUID, and stale-version tests.

**Acceptance:** A user from organization A cannot see, update, delete, enumerate, or search organization B's incidents. Unauthorized access fails without leaking data. Only after this gate may the API be considered for public exposure.

**Implemented locally:** Append-only V4 preserves legacy incidents with NULL ownership, enforces ownership for new writes, and adds the foreign key/indexes. Scoped CRUD/list/filter/count queries and SQL write predicates enforce persisted membership and ADMIN/ENGINEER/VIEWER permissions; global routes are denied. PATCH and DELETE retain atomic version conflict checks. Regression, role/tenant, legacy, migration, direct service/repository, rollback, and concurrent-write tests are present. See [M9B_INCIDENT_TENANCY.md](M9B_INCIDENT_TENANCY.md) for the contract and actual verification results: `clean test` and `verify` each passed all 45 tests on Java 21 with no failures/errors/skips. The user approved M9B for local development. The separate [local Keycloak smoke test](LOCAL_KEYCLOAK_SMOKE.md) now verifies signed human/CLI tokens and real wrong-audience rejection using a disposable backend/database. Milestone 10 is approved for local development; see its record below.

## M10 — Knowledge base and document management — APPROVED for local development

**Deliver:** Organization-owned document records, versions, upload status, source metadata, size/type limits, lifecycle API, and safe deletion behavior. Begin with PDF (text-based), Markdown, and TXT.

**Acceptance:** Only authorized users can list/download/manage their documents; file validation and version-history tests pass. No AI required yet.

**Implemented locally:** Organization-scoped PDF/Markdown/TXT uploads; validated bounded content, measured size and SHA-256; immutable revision attempt history; pagination/status filtering; version-aware title/revision/deletion operations; private POSIX storage behind a small adapter; crash/failure compensation and startup cleanup; current membership/RBAC checks in services and scoped SQL; composite tenant foreign keys; tombstone deletion blocking all historical downloads. Append-only V5 preserves V1–V4 and existing data. No extraction, ingestion, AI, cloud or frontend was added. Storage/runtime is deliberately single-process. See [M10_DOCUMENT_MANAGEMENT.md](M10_DOCUMENT_MANAGEMENT.md) for API examples, final verification evidence, recovery/deletion policy and limits. Final `clean test` and `verify` each passed 79 tests with zero failures/errors/skips; the M10 record includes build verification. The user approved M10 for local development; M11 is implemented below.

## M11 — Durable ingestion with AWS S3/SQS — APPROVED for local development

**Deliver:** Explicit private LOCAL/S3 storage providers; PostgreSQL transactional outbox and
preparation jobs; leased SQS dispatcher; independently executable Java verification worker;
idempotency, bounded processing retries, dead-letter handling, recovery and operational status.
LocalStack only; no real AWS provisioning. Preserve existing M10 upload/download/revision/tombstone
behavior. This milestone excludes extraction, parser/chunker, embeddings, RAG and presigned uploads.

**Implemented:** Append-only V6 preserves old revisions as LOCAL and adds tenant-constrained jobs/events.
Conditional SHA-256 S3 writes, token/generation-fenced job completion, concurrent dispatch/claim safety,
missing-delivery reconciliation, explicit development retry, scoped status reads, worker health and
Micrometer measurements. Local Compose/init scripts and sanitized configuration are provided. The API
retains single-writer recovery; independently executable workers use read-only legacy filesystem access.

**Acceptance evidence:** `clean test` and `verify` each passed 106 tests with zero failures/errors/skips, including existing
45 incident/organization tests and all M10 regressions, private S3 metadata/roundtrip checks, SQL rollback,
lease/crash simulations, duplicate/out-of-order messages, live SQS visibility/DLQ redrive, cancelled/deleted
work, tenant UUID forgery and a separate scheduled Java worker. See [M11_DURABLE_INGESTION.md](M11_DURABLE_INGESTION.md)
for exact final commands, build evidence and emulator limitations. VERIFIED is preparation only; no index
or search existed at the M11 checkpoint. The user approved M11 and authorized M12 on 2026-10-14.

## M12 — Extraction, indexing and hybrid retrieval — APPROVED for local development

**Deliver:** Embedding-provider adapter, pgvector schema, full-text search, permission-aware hybrid retrieval, chunk/source references, and an initial evaluation dataset.

**Acceptance:** Search results are tenant-authorized; a fixed set of questions measures recall@k and relevance; results are reproducible and saved under `evaluations/`.

**Implemented locally:** Resource-constrained isolated PDFBox validation/extraction, UTF-8 Markdown/TXT, deterministic source-aware chunks, real local nomic v1.5 embeddings through Spring AI 2.0.1/Ollama, append-only V7 pgvector/full-text schema, typed M11 outbox/SQS indexing events, renewable fenced leases, extraction checkpoints, retries/recovery, atomic generation publication, safe reindex/backfill commands and organization-scoped keyword/cosine/RRF APIs. Current-revision/tombstone/membership filtering occurs inside SQL before top-K. Defaults require explicit model/indexing enablement; no private data leaves local configured inference.

**Evidence:** Final `clean test` and `verify` each passed 152 tests with no failures/errors/skips; complete build evidence is recorded in [M12](M12_EXTRACTION_AND_RETRIEVAL.md). The separate real-model evaluation uses five synthetic documents/ten chunks and ten labeled exact/paraphrase queries per tenant: Recall@5/MRR@5 0.60/0.60 keyword, 1.00/1.00 semantic and hybrid, zero foreign results. Dataset and actual observations are saved under `evaluations/m12/`; these do not establish production quality. ADR-0007 and local downloads were approved before implementation. The user approved M12 for local development in the M13 request. Historical M12 verification/results remain unchanged.


## M13 — Grounded persistent AI chat — APPROVED for local development

**Deliver:** Private organization/creator-scoped conversations, durable ordered/idempotent turns,
local Spring AI/Ollama chat, bounded M12 hybrid context, validated real citations, canonical abstention,
transitive historical source invalidation, failure/deadline/admission handling and safe operational metrics.
No tool calling or streaming is implemented; both require later scope/authorization review.

**Acceptance:** Authorized users can continue persistent conversations; references resolve only to live
permitted sources; deleted/superseded/revoked evidence cannot bypass historical visibility; failures/retries
and concurrent submissions are safe; deterministic tests and real local model observations are documented.
See [M13_RAG_CHAT.md](M13_RAG_CHAT.md) and [ADR-0008](DECISIONS/0008-private-conversations-and-validated-rag-evidence.md).
**Local synthetic quality checkpoint completed:** The approved pinned 7B model, batch 64,
four CPU threads and 120-second bound produced six supported answers/four abstentions,
10/10 expected statuses, zero foreign leakage and 9/9 valid citations. Manual complete
citation support was 6/6 accepted answers. This tiny benchmark is not a production accuracy
claim. CPU turns take roughly 52–97 seconds; a 60-second bound was insufficient. The earlier
1.5B/OOM/timeout observations are retained. Final regression evidence is recorded in M13;
the M14 request approves M13 locally. Historical evaluation evidence remains unchanged.

## M14 — Secure read-only MCP — IMPLEMENTED LOCALLY; REVIEW PENDING

**Deliver:** Disabled-by-default stateless Streamable HTTP `/mcp` with six explicit read-only
organization, incident, document, search and exact-source tools. Reuse existing services and
SQL authorization, propagate the original validated caller JWT across thread changes, enforce
separate MCP resource-URI versus REST audience validation, and bound transport/tool resources.
No MCP session persistence, schema migration, write tools, prompts, resources or chat generation.

**Security:** Protected-resource metadata, header-only bearer authentication, strict loopback
Host/Origin guards, generic errors, request/result limits, concurrency/deadline/rate admission
and fixed-cardinality metrics. Existing Keycloak stays unchanged; approved disposable Keycloak
26.8 enables experimental resource indicators for real Code + PKCE/client-credentials tests.
The official Java SDK client initializes, discovers and invokes all six tools. Tests include
foreign IDs, visibility, revocation, concurrent identities, session reuse, negative tokens and
independent REST behavior. The requested M13 marker correction preserves normal Markdown.

**Profile and evidence:** Spring AI 2.0.1 / official SDK 2.0.0 negotiate through 2025-11-25;
2026-07-28, automatic client registration and browser Inspector CORS are not claimed. See
[M14_SECURE_MCP.md](M14_SECURE_MCP.md) for exact final commands/results, schemas, client setup,
security review and remaining limits; [ADR-0009](DECISIONS/0009-stateless-read-only-mcp.md)
records transport/OAuth decisions. Stop for M14 review; do not start M15.

## M15 — AI safety and quality evaluation — PLANNED

**Deliver:** Prompt-injection fixtures, tenant-leak tests, retrieval comparison (keyword/vector/hybrid), citation audits, abstention checks, and latency/cost benchmarks.

**Acceptance:** Reproducible report with actual measured outcomes and documented limitations; no invented quality numbers.

## M16 — Minimal React frontend — PLANNED

**Deliver:** Login, organization switching, incident management, document library, source search, and AI investigation interface. Use TypeScript; focus on core workflows.

**Acceptance:** Critical authenticated user journeys pass browser tests and respect access control.

## M17 — Packaging, deployment, and portfolio release — PLANNED

**Deliver:** Docker setup, CI test gates, health/metrics/tracing, Terraform and controlled AWS demo deployment (ECS/RDS/S3/SQS as justified), architecture diagrams, README, screenshots/video, and a synthetic demonstration dataset.

**Acceptance:** Clean checkout can build/test; cloud resources can be deployed and torn down with approval; costs and security caveats documented; real demo works end-to-end.

## Definition of done — every milestone

- [ ] Scope matches this milestone; no unrelated speculative features.
- [ ] New functionality has positive, negative, and security tests appropriate to its risks.
- [ ] `cd backend && ./mvnw clean test` passes, or failures/blockers are explicitly documented.
- [ ] Migrations are append-only, reversible where practical, and existing data remains safe.
- [ ] Authentication, tenant ownership, and external effects reviewed.
- [ ] API contracts and documentation updated when behavior changes.
- [ ] Diff reviewed for secrets, logging of sensitive data, and unsafe defaults.
- [ ] Agent provides a handoff summary and **stops for approval**.

## Scope control

A milestone can be split into smaller tasks if risks are high. Don't silently start the next milestone, expand to microservices, implement payments, or provision AWS. Proposed design changes should go through `docs/DECISIONS/` first.
