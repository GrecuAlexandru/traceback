# ADR-0001 — Modular monolith with separate runtime roles

- **Status:** Accepted
- **Date:** 2026-10-13

## Context

Traceback needs incident management, tenant security, document ingestion, search, RAG, and MCP. A portfolio project must demonstrate clean boundaries without unnecessary deployment complexity.

## Decision

Use one Java/Spring Boot codebase organized by business feature. Controllers, services, repositories, and DTOs live within feature packages. Long-running ingestion may run as a separate worker process built from the same codebase when required; HTTP endpoints must not wait for embedding of large documents.

## Alternatives considered

- **Microservices from day one:** unnecessary network, operational, and testing overhead.
- **Everything in controllers:** difficult to test and unsafe for authorization consistency.

## Consequences

- Straightforward local development, integration tests, and debugging.
- Module boundaries must be maintained by convention and tests.
- If future scaling requires independent services, extract only after measuring the need.

## Implementation status

**CURRENT:** Single Spring Boot application with incident/security/API/user/organization packages. M9A organization controllers delegate to transactional services and repositories.
**PLANNED:** AI-specific modules and cloud deployment.


**CURRENT M10:** The `knowledge` feature package owns thin HTTP controllers, application services, explicit JDBC persistence records, DTOs, validation, upload recovery and a small private storage interface. No worker runtime or new service was introduced.

**CURRENT M11:** The same executable build runs API and independent worker roles. Worker HTTP permits health only and skips API upload recovery. Preparation/queue scheduling reuses the `knowledge` storage abstraction and an `ingestion` feature package; no microservice/codebase split. See [ADR-0006](0006-ingestion-leases-and-storage-compatibility.md).
