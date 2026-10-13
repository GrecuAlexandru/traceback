# Traceback — Architecture

**Document status:** Living technical specification.  
**Baseline inspected:** Local repository on `main`, 2026-10-13; M9A/M9B approved for local development; local Keycloak follow-up verified; M10 implemented locally and pending user review.
**Rule:** Sections labeled **CURRENT** describe code found in the repository; **PLANNED** sections are not implemented claims.

## 1. Purpose

Traceback is a technical incident and engineering-knowledge platform. Teams will record incidents and resolutions, ingest runbooks and postmortems, find similar failures, and ask an AI assistant for evidence-backed investigation suggestions. A secured MCP server will make selected knowledge tools available to other AI clients.

The product must remain useful without AI: incident management, knowledge storage, authorization, and conventional search are first-class features.

## 2. Current implementation (verified in repository source)

- **Backend:** Java 21 target, Spring Boot 4.1.1, Maven Wrapper (`backend/`).
- **HTTP API:** `GET /api/v1/ping`, `/api/v1/me`, incident CRUD under `/api/v1/organizations/{organizationId}/incidents` (global incident routes denied), and `POST`/`GET /api/v1/organizations` (M9A).
- **Incidents:** Organization-owned new incidents; service membership checks and tenant/identity-scoped read/count queries; ADMIN/ENGINEER SQL write predicates; VIEWER read only; pagination/status filtering; version-aware PATCH and DELETE; `@Version` plus atomic SQL compare-and-swap; generic 403/404/409 errors. Repository exposes no unscoped CRUD methods.
- **Documents (M10):** Organization-scoped upload/list/metadata/title update/download/revision history/tombstone deletion; ADMIN/ENGINEER writes, VIEWER reads. Validated PDF/Markdown/TXT with measured limits and SHA-256. V5 adds documents and immutable revision attempts with composite tenant foreign keys. A private POSIX storage adapter uses opaque keys, atomic promotion, file/directory fsync and safe startup recovery; database/filesystem locks permit one runtime per database/root. See [M10_DOCUMENT_MANAGEMENT.md](M10_DOCUMENT_MANAGEMENT.md) for contracts, consistency and limitations. No extraction or indexing exists.
- **Persistence:** PostgreSQL; Flyway V1/V2 for incidents and the pre-existing `V3__create_organizations_and_users.sql` for users, organizations, and memberships; append-only V4 adds incident ownership, a foreign key, tenant indexes, and a NOT VALID ownership check that preserves unassigned legacy rows but rejects new unowned writes. Hibernate schema validation remains enabled. V1–V3 are unchanged.
- **Authentication:** Spring Security OAuth2 resource server with JWT issuer `http://localhost:8180/realms/traceback` and expected audience `traceback-api`; `/api/v1/ping` and `/actuator/health` are public.
- **Organizations:** Application users keyed by `(issuer, subject)`; `ADMIN`/`ENGINEER`/`VIEWER` memberships; atomic creation with an initial administrator; listing joins only the authenticated identity's memberships. PostgreSQL `ON CONFLICT DO NOTHING` and an explicit `READ_COMMITTED` transaction handle concurrent first use. Listing does not provision users.
- **M10 verification:** Final `clean test` and `verify` each passed all 79 tests (45 existing + 34 document/storage/migration tests), with no failures/errors/skips; see the M10 verification record for build evidence. New real HTTP coverage verifies servlet multipart 413 formatting; other document authorization requests use synthetic principals.
- **Tests:** JUnit/MockMvc and Testcontainers PostgreSQL, including membership isolation, bootstrap denial, constraints, forced rollback, concurrent first use, V2-to-V3 and V3-to-V4 upgrades preserving incidents, legacy non-exposure, direct service/repository authorization, and concurrent version-aware mutations. The final M9B suite passed 45 tests on Java 21; see its verification record. Organization and incident API tests use simulated validated JWTs and a mocked decoder; those suites do not verify real-token cryptography or mapper configuration. A separate [local browser PKCE smoke test](LOCAL_KEYCLOAK_SMOKE.md) now verifies real Keycloak tokens and backend signature/issuer/audience enforcement.
- **Local Compose:** PostgreSQL (`pgvector/pgvector:pg17`) on host port 5433 and Keycloak (`26.8.0`) on host port 8180, both bound to `127.0.0.1`. Compose validates and both services were running during M9A and M9B verification. Keycloak discovery confirms the configured issuer; human-token bootstrap and CLI denial have now passed a real-token smoke test using a disposable backend/database.
- **Flyway configuration:** `classpath:db/migration` is already configured correctly. Root `.gitignore` excludes `.env` and `.env.*` at any depth, with sanitized `.env.example` files allowed.

**CURRENT SECURITY BOUNDARY:** Incident and document APIs are tenant-scoped with membership/RBAC enforcement; legacy unassigned incidents are quarantined and global incident routes are denied. Dedicated local PKCE clients and their human/audience mappings are configured; real human, service-account, missing/invalid-signature, and wrong-audience token checks passed for the prior organization/incident baseline. M10 reuses that unchanged authentication configuration and adds simulated-principal authorization tests. Keep the application local pending a separate deployment review. See [LOCAL_KEYCLOAK_SMOKE.md](LOCAL_KEYCLOAK_SMOKE.md) for reproducible setup and exact evidence. See [M9B_INCIDENT_TENANCY.md](M9B_INCIDENT_TENANCY.md).

## 3. Intended architecture (PLANNED)

```text
React + TypeScript UI
         |
         v
Spring Boot API  <---->  OIDC identity provider
  |     |     |
  |     |     +----> LLM / embedding providers (through controlled adapters)
  |     +----------> Redis (optional cache/rate limits)
  +----------------> PostgreSQL + pgvector
  |                      ^
  +--> S3 private uploads | 
  +--> DB outbox --> SQS --> Java ingestion worker --> parse/chunk/embed/index
  |
  +--> authenticated MCP endpoint --> same authorized application services
```

**Deployment principle:** one modular Java codebase; API and background worker may run as separate processes from the same build. Avoid premature microservices.

## 4. Module boundaries

| Module | Responsibility | Must not own |
| --- | --- | --- |
| `api` | Cross-cutting HTTP error formatting, health/identity endpoints | Business authorization rules |
| `security` | JWT validation configuration, authenticated identity/context | Tenant membership stored in JWT as sole authority |
| `incident` | Incident lifecycle, versions, CRUD, pagination, tenant/identity-scoped queries and version-aware SQL mutations | Keycloak credential handling |
| `user` | Application identity mapped by issuer + subject | Password storage |
| `organization` | Organizations, memberships, human bootstrap policy, identity-scoped listing and current membership/role authorization for incidents and documents | Cross-tenant data access shortcuts |
| `knowledge` (current M10) | Tenant-owned document metadata, immutable revision attempts, lifecycle, validation, private storage abstraction and recovery | Untrusted content interpreted as commands |
| `ingestion` (planned) | Durable processing jobs, outbox, retries, idempotency | Synchronous long-running HTTP work |
| `search` (planned) | Keyword/vector/hybrid search with permission filters | Returning raw unauthorized chunks |
| `rag` (planned) | Retrieval orchestration, prompt assembly, citations, evaluation | Authorization delegated to the LLM |
| `mcp` (planned) | Secure tools/resources backed by services | Separate bypass of business permissions |

Use feature packages with controllers/services/repositories/DTOs rather than giant global technical-layer packages. Do not couple the domain model directly to provider SDKs.

## 5. Identity, tenancy, and authorization (CURRENT M9A/M9B/M10; AI tenancy PLANNED)

- Keycloak/OIDC authenticates principals. Application users are identified by **issuer + subject**.
- A user may be a member of multiple organizations with different roles: `ADMIN`, `ENGINEER`, `VIEWER`.
- Memberships and organization permissions live in PostgreSQL, not in trusted client-supplied fields.
- **CURRENT:** Every new incident has explicit organization ownership; unassigned pre-V4 incidents are inaccessible. **CURRENT M10:** Documents and immutable revisions have mandatory organization ownership and scoped authorization. **PLANNED:** Chunks, search results, chats, and ingestion jobs require the same tenant boundaries.
- Resolve and authorize organization context **before** all reads, writes, retrieval, downloads, and MCP actions.
- Prefer URLs of the form `/api/v1/organizations/{orgId}/incidents`. Do not leave an unscoped legacy incident endpoint as a security bypass.
- Deny by default. Return consistent 401/403/404 responses without revealing private incident existence across tenants.
- Maintain `@Version` semantics with atomic SQL version comparison/increment for PATCH and expected-version deletion. Checks occur only after authorized tenant lookup.
- **CURRENT legacy policy:** V4 leaves pre-tenant incidents with NULL ownership, behind a NOT VALID ownership check and scoped queries. No organization gains automatic access. Future assignment/archive and final NOT NULL validation need a separate reviewed operation.
- **CURRENT bootstrap policy:** Organization, incident and document service entry points require the trusted signed claim `traceback_principal_type=human` plus a nonblank issuer and subject within the V3 column limits. Absent, differently typed, or machine-principal claims are denied with 403 before database access. Email, username, JWT roles, request IDs, and client-supplied roles do not confer organization permissions.
- This claim must be issuer-controlled and restricted to a dedicated human-login client with service accounts disabled; no user-editable attribute or generic-client mapper may grant it. The local `traceback-web-test` client now owns that mapping; generic clients such as `traceback-cli` do not inherit it. Tokens without the human classification still fail closed for organizations and incidents. See [M9A_ORGANIZATIONS.md](M9A_ORGANIZATIONS.md) for the API and trust contract.

## 6. Persistence and migrations

PostgreSQL is the system of record. Spring Data JPA is appropriate for ordinary domain entities; explicit SQL/JDBC may be used when full-text/vector retrieval requires it. Flyway owns schema changes; Hibernate validates them.

**PLANNED:** Add pgvector embeddings plus full-text search indexes with tenant-aware filtering. A migration must preserve deployed data, be reviewable, and have integration tests. Never silently switch `ddl-auto` to `update`.

## 7. Document management (CURRENT M10) and ingestion (PLANNED)

**CURRENT M10:** Synchronous bounded file validation and private filesystem storage, separate from ingestion. A committed UPLOADING revision reservation precedes file writes; atomic storage promotion precedes transactional AVAILABLE publication. Failed attempts remain auditable and inaccessible; startup reconciles interrupted attempts and retries failed object cleanup. Document revision numbers are independent of optimistic metadata versions. A row lock plus expected version and one-pending-revision constraint serialize mutations. Downloads reauthorize immediately before reading and verify size/checksum. DELETE creates a tombstone blocking all revision content while retaining private files/history; future retrieval and purge must honor it. The local runtime deliberately permits one backend process per database/root. See [M10 design and test evidence](M10_DOCUMENT_MANAGEMENT.md).

**PLANNED M11 target workflow:**

1. Authorized caller creates a document/upload record scoped to an organization.
2. Backend creates a short-lived presigned URL for a private S3 object.
3. Server verifies successful upload, type/size limits, and expected object metadata.
4. Database transaction persists an ingestion job and an **outbox event**.
5. Publisher sends the event to SQS; Java worker fetches, parses, chunks, embeds, and stores authorized, versioned content.
6. Worker persists final state; retries/duplicates do not duplicate active chunks. Failed attempts use bounded retries and a DLQ.

SQS should be treated as at-least-once delivery. Implement idempotency and recovery before claiming durability.

## 8. Search, AI, and MCP (PLANNED)

- Retrieval stages: tenant/permission filtering -> keyword and/or vector retrieval -> hybrid ranking -> verified source selection -> LLM response -> citation validation.
- Start with PostgreSQL full-text search and pgvector; benchmark before introducing approximate indexes or rerankers.
- Responses must identify supporting sources and explicitly abstain when evidence is insufficient. Claims of accuracy require a reproducible evaluation dataset.
- Treat documents, user uploads, retrieval snippets, and MCP tool output as **untrusted data**. Prevent prompt injection from modifying system policy or causing unauthorized actions.
- MCP tools initially read-only (for example `search_incidents`, `get_incident`, `search_knowledge`). Use the **same** tenant authorization services as REST endpoints.
- No automatic production remediation or arbitrary shell execution by the assistant.

## 9. Local development and verification

```bash
# From repository root, if services are configured:
docker compose config --quiet
docker compose up -d

# From backend/:
./mvnw clean test
./mvnw spring-boot:run
```

A local Keycloak instance or equivalent OIDC issuer is required for real-token tests and certain application startup configurations. Local Compose already includes Keycloak. Organization and incident tests isolate application authorization with mocked decoders and simulated JWTs; PostgreSQL migrations and SQL authorization are exercised in disposable containers. The separate Python standard-library PKCE script demonstrates a real browser human login against Keycloak, plus valid CLI-token authorization denial and wrong-audience rejection. Its verified run used only an isolated disposable application database, preserving the persistent database and volumes.

## 10. Explicit non-goals for the portfolio MVP

No payment system, mobile app, Kubernetes, Kafka, autonomous remediation, multi-region deployment, or unrestricted agent execution. A clean Java backend, verifiable RAG/MCP integrations, and reliable ingestion matter more than broad features.

For milestones and acceptance criteria, see [ROADMAP.md](ROADMAP.md). For trade-offs, see [DECISIONS/README.md](DECISIONS/README.md).
