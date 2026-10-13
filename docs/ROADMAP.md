# Traceback — Development Roadmap

**Last source review:** GitHub `main`, 2026-10-13. Review local changes before acting.  
**Execution rule:** Work on **one milestone per agent task**. Do not start the next milestone until the user accepts the previous one.

## Status definitions

- **Code present:** implementation found in GitHub; runtime/tests may still require verification.
- **Next:** the next milestone to implement.
- **Planned:** not implemented or not verified as complete.
- **Accepted:** tests, demonstration, security checks, and user review completed; only mark after evidence is available.

## Baseline — code present

- Java 21 target / Spring Boot 4.1.1 / Maven Wrapper.
- Incident CRUD, filtering/pagination, optimistic locking and `409 Conflict` handling.
- PostgreSQL and Flyway V1/V2.
- JWT resource-server configuration and authenticated identity endpoint.
- Testcontainers/MockMvc test code; **passing tests not independently confirmed in this document**.

**Preflight before Milestone 9A:** Verify tests; reconcile Keycloak local setup against the committed Compose file; check the Flyway migration location; ensure root `.env` files cannot be committed. Fix only confirmed blockers in a narrowly scoped change.

## M9A — Users, organizations, memberships — NEXT

**Deliver:**

- Flyway V3 for application users (issuer + subject), organizations, and membership roles.
- `ADMIN`, `ENGINEER`, `VIEWER` membership model with uniqueness and foreign keys.
- Authenticated organization-create and list-my-organizations endpoints.
- Atomic creation of organization plus its initial administrator membership.
- Explicit service-account/organization-bootstrap policy; no arbitrary privilege escalation by generic machine clients.
- Testcontainers tests for creation, same-user listing, different-user isolation, invalid input, and concurrent first-time identity creation (or an explicitly recorded follow-up).

**Acceptance:** Users can only list their memberships; failures leave no orphaned organizations; existing incident rows and prior migrations are untouched; all relevant tests pass. **Do not** claim incident tenant isolation yet.

## M9B — Tenant-scoped incidents and RBAC — PLANNED

**Deliver:**

- Versioned migration adding organization ownership to incidents without exposing legacy rows. Produce and review a safe plan for existing records before making the column mandatory.
- Organization-scoped incident CRUD/list/pagination; server-side membership and role checks on every path.
- `ADMIN`/`ENGINEER` write permissions; `VIEWER` read only; consistent missing/foreign-ID handling.
- Decommission or strictly secure legacy global `/api/v1/incidents` routes; never leave an unscoped bypass.
- Cross-tenant, role, guessing-UUID, and stale-version tests.

**Acceptance:** A user from organization A cannot see, update, delete, enumerate, or search organization B's incidents. Unauthorized access fails without leaking data. Only after this gate may the API be considered for public exposure.

## M10 — Knowledge base and document metadata — PLANNED

**Deliver:** Organization-owned document records, versions, upload status, source metadata, size/type limits, lifecycle API, and safe deletion behavior. Begin with PDF (text-based), Markdown, and TXT.

**Acceptance:** Only authorized users can list/download/manage their documents; file validation and version-history tests pass. No AI required yet.

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
