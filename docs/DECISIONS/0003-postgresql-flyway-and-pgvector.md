# ADR-0003 — PostgreSQL, Flyway, and pgvector

- **Status:** Accepted
- **Date:** 2026-10-13

## Context

Traceback needs durable relational records, transaction guarantees, incident history, authorization relationships, and later semantic retrieval.

## Decision

Use PostgreSQL as the system of record. Apply schema changes through immutable, versioned Flyway migrations; keep Hibernate `ddl-auto: validate`. Use Spring Data JPA for conventional relational persistence and SQL/JDBC when search requires specialized queries. Use PostgreSQL full-text search and pgvector before adding separate search infrastructure.

## Alternatives considered

- **Hibernate auto-DDL updates:** hides migration history and can alter schemas unexpectedly.
- **A separate vector database immediately:** adds synchronization and operational complexity without established scale requirements.
- **In-memory database for integration tests:** doesn't faithfully test PostgreSQL behavior.

## Consequences

- Tests should run on real PostgreSQL through Testcontainers.
- Migrations must preserve existing rows and be safe across upgrades.
- Evaluate query/index performance and tenant filtering before enabling approximate vector indexes.

## Implementation status

**CURRENT:** PostgreSQL via Docker, JPA, Flyway V1/V2, Testcontainers test code.  
**PLANNED:** pgvector embeddings, full-text/hybrid search, schema evolution for organizations/documents.
