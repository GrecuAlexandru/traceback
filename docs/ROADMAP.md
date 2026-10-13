# Traceback — Development Roadmap

**Last source review:** Local `main`, 2026-10-13, M9A/M9B approved for local development; local Keycloak follow-up verified; M10 implemented locally and pending review.
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

**Implemented locally:** Append-only V4 preserves legacy incidents with NULL ownership, enforces ownership for new writes, and adds the foreign key/indexes. Scoped CRUD/list/filter/count queries and SQL write predicates enforce persisted membership and ADMIN/ENGINEER/VIEWER permissions; global routes are denied. PATCH and DELETE retain atomic version conflict checks. Regression, role/tenant, legacy, migration, direct service/repository, rollback, and concurrent-write tests are present. See [M9B_INCIDENT_TENANCY.md](M9B_INCIDENT_TENANCY.md) for the contract and actual verification results: `clean test` and `verify` each passed all 45 tests on Java 21 with no failures/errors/skips. The user approved M9B for local development. The separate [local Keycloak smoke test](LOCAL_KEYCLOAK_SMOKE.md) now verifies signed human/CLI tokens and real wrong-audience rejection using a disposable backend/database. Milestone 10 is now implemented locally and pending review; see its record below.

## M10 — Knowledge base and document management — IMPLEMENTED LOCALLY; REVIEW PENDING

**Deliver:** Organization-owned document records, versions, upload status, source metadata, size/type limits, lifecycle API, and safe deletion behavior. Begin with PDF (text-based), Markdown, and TXT.

**Acceptance:** Only authorized users can list/download/manage their documents; file validation and version-history tests pass. No AI required yet.

**Implemented locally:** Organization-scoped PDF/Markdown/TXT uploads; validated bounded content, measured size and SHA-256; immutable revision attempt history; pagination/status filtering; version-aware title/revision/deletion operations; private POSIX storage behind a small adapter; crash/failure compensation and startup cleanup; current membership/RBAC checks in services and scoped SQL; composite tenant foreign keys; tombstone deletion blocking all historical downloads. Append-only V5 preserves V1–V4 and existing data. No extraction, ingestion, AI, cloud or frontend was added. Storage/runtime is deliberately single-process. See [M10_DOCUMENT_MANAGEMENT.md](M10_DOCUMENT_MANAGEMENT.md) for API examples, final verification evidence, recovery/deletion policy and limits. Final `clean test` and `verify` each passed 79 tests with zero failures/errors/skips; the M10 record includes build verification. User acceptance remains pending; M11 has not begun.

## M11 — Durable ingestion with AWS S3/SQS — PLANNED

**Deliver:** Private S3 upload, presigned URLs, upload verification, PostgreSQL outbox, SQS publisher, Java worker, parser/chunker, retries, idempotency, dead-letter handling, and controlled cleanup.

**Acceptance:** A real or integration-test worker crash and duplicate SQS delivery cannot create duplicate active chunks or mark a failed document ready; jobs recover or fail visibly. No cloud provisioning without explicit approval.

## M12 — Retrieval and RAG foundations — PLANNED

**Deliver:** Embedding-provider adapter, pgvector schema, full-text search, permission-aware hybrid retrieval, chunk/source references, and an initial evaluation dataset.

**Acceptance:** Search results are tenant-authorized; a fixed set of questions measures recall@k and relevance; results are reproducible and saved under `evaluations/`.

## M13 — Grounded AI chat and tools — PLANNED

**Deliver:** Spring AI chat, conversation persistence, bounded context, controlled tool calling, validated citations, insufficient-evidence behavior, timeouts, and usage/cost tracking.

**Acceptance:** Answers cite only authorized real sources, invalid citations are rejected, and the assistant abstains when evidence is absent. Tests cover provider failures.

## M14 — MCP server and client — PLANNED

**Deliver:** Authenticated, read-only MCP tools backed by the same application services and tenant checks as REST; a restricted, demonstrable external MCP client integration.

**Acceptance:** Tool discovery and normal requests work; missing auth, invalid audience, denied organization access, and suspicious tool inputs are rejected; independent client smoke test recorded.

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
