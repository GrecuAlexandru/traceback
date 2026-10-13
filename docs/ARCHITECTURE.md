# Traceback — Architecture

**Document status:** Living technical specification.  
**Baseline inspected:** GitHub `main`, 2026-10-13. Local uncommitted work may be ahead.  
**Rule:** Sections labeled **CURRENT** describe code found in the repository; **PLANNED** sections are not implemented claims.

## 1. Purpose

Traceback is a technical incident and engineering-knowledge platform. Teams will record incidents and resolutions, ingest runbooks and postmortems, find similar failures, and ask an AI assistant for evidence-backed investigation suggestions. A secured MCP server will make selected knowledge tools available to other AI clients.

The product must remain useful without AI: incident management, knowledge storage, authorization, and conventional search are first-class features.

## 2. Current implementation (verified in repository source)

- **Backend:** Java 21 target, Spring Boot 4.1.1, Maven Wrapper (`backend/`).
- **HTTP API:** `GET /api/v1/ping`, `/api/v1/me`, and incident CRUD under `/api/v1/incidents`.
- **Incidents:** JPA entity/repository/service/controller, request and response DTOs, status filtering, pagination, version-aware PATCH, optimistic locking (`@Version`), and conflict handling.
- **Persistence:** PostgreSQL; Flyway `V1__create_incidents.sql` and `V2__add_incident_version.sql`; Hibernate schema validation.
- **Authentication:** Spring Security OAuth2 resource server with JWT issuer `http://localhost:8180/realms/traceback` and expected audience `traceback-api`; `/api/v1/ping` and `/actuator/health` are public.
- **Tests:** JUnit/MockMvc integration-test code using Testcontainers PostgreSQL and simulated JWTs. Presence of tests is not a claim that they have passed in this environment.
- **Local Compose on GitHub main:** PostgreSQL using the `pgvector/pgvector:pg17` image on host port 5433. The committed Compose file does **not** yet define Keycloak, even though backend configuration expects it. Check local files before modifying.
- **Config discrepancy to verify:** `application.yaml` currently sets `spring.flyway.locations: classpath:db/`, while migrations are under `classpath:db/migration/`. Confirm discovery and explicitly configure the migration directory if needed.

**CURRENT SECURITY LIMITATION:** Incidents are authenticated but **not yet scoped to organizations**. Never expose this API publicly or treat JWT authentication alone as tenant isolation.

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
| `incident` | Incident lifecycle, versions, CRUD, pagination, tenant-scoped queries (planned) | Keycloak credential handling |
| `user` (planned) | Application identity mapped by issuer + subject | Password storage |
| `organization` (planned) | Organizations, memberships, roles, authorization policy | Cross-tenant data access shortcuts |
| `knowledge` (planned) | Document metadata, ownership, versions, source references | Untrusted content interpreted as commands |
| `ingestion` (planned) | Durable processing jobs, outbox, retries, idempotency | Synchronous long-running HTTP work |
| `search` (planned) | Keyword/vector/hybrid search with permission filters | Returning raw unauthorized chunks |
| `rag` (planned) | Retrieval orchestration, prompt assembly, citations, evaluation | Authorization delegated to the LLM |
| `mcp` (planned) | Secure tools/resources backed by services | Separate bypass of business permissions |

Use feature packages with controllers/services/repositories/DTOs rather than giant global technical-layer packages. Do not couple the domain model directly to provider SDKs.

## 5. Identity, tenancy, and authorization (PLANNED)

- Keycloak/OIDC authenticates principals. Application users are identified by **issuer + subject**.
- A user may be a member of multiple organizations with different roles: `ADMIN`, `ENGINEER`, `VIEWER`.
- Memberships and organization permissions live in PostgreSQL, not in trusted client-supplied fields.
- Each incident, document, chunk, search result, chat, and ingestion job must have explicit tenant ownership.
- Resolve and authorize organization context **before** all reads, writes, retrieval, downloads, and MCP actions.
- Prefer URLs of the form `/api/v1/organizations/{orgId}/incidents`. Do not leave an unscoped legacy incident endpoint as a security bypass.
- Deny by default. Return consistent 401/403/404 responses without revealing private incident existence across tenants.
- Maintain the `@Version` optimistic-locking contract for incident updates and eventually version-aware deletion.
- Existing pre-tenant incidents require an explicit safe migration/quarantine plan; do not automatically give every new organization access.
- Do not allow arbitrary machine-to-machine clients to create privileged human organizations without an explicit policy.

## 6. Persistence and migrations

PostgreSQL is the system of record. Spring Data JPA is appropriate for ordinary domain entities; explicit SQL/JDBC may be used when full-text/vector retrieval requires it. Flyway owns schema changes; Hibernate validates them.

**PLANNED:** Add pgvector embeddings plus full-text search indexes with tenant-aware filtering. A migration must preserve deployed data, be reviewable, and have integration tests. Never silently switch `ddl-auto` to `update`.

## 7. Document ingestion (PLANNED)

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

A local Keycloak instance or equivalent OIDC issuer is required for real-token tests and certain application startup configurations. Current `main` Compose may need reconciliation; avoid assuming a Keycloak container exists.

## 10. Explicit non-goals for the portfolio MVP

No payment system, mobile app, Kubernetes, Kafka, autonomous remediation, multi-region deployment, or unrestricted agent execution. A clean Java backend, verifiable RAG/MCP integrations, and reliable ingestion matter more than broad features.

For milestones and acceptance criteria, see [ROADMAP.md](ROADMAP.md). For trade-offs, see [DECISIONS/README.md](DECISIONS/README.md).
