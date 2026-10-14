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

**CURRENT:** PostgreSQL via Docker, JPA, Flyway V1/V2/V3/V4, Testcontainers tests. M9A maps the existing V3 schema without editing it; a V2-to-V3 upgrade test preserves incident fields and version. Identity insertion uses PostgreSQL `ON CONFLICT (issuer, subject) DO NOTHING` inside a READ_COMMITTED transaction to avoid aborting concurrent first-use requests.

M9B appends V4 without editing prior migrations. NULL legacy ownership remains quarantined behind a NOT VALID check; new inserts/updates require a real organization FK. Tenant/status/date indexes support scoped lists. Permission predicates are included in reads/counts and mutations; SQL UPDATE/DELETE compare expected versions atomically. A V3-to-V4 upgrade test preserves legacy fields and verifies constraints. Final legacy assignment/archive and NOT NULL validation remain a separate reviewed operation.

**PLANNED:** pgvector embeddings, full-text/hybrid search and future ingestion schema evolution.


**CURRENT M10:** Append-only V5 creates tenant-owned documents and immutable content revision attempts with composite foreign keys, checks, unique/index constraints and a revision-update trigger. Explicit JDBC mapping and row locks serve the two-phase upload workflow; metadata version and content revision number are distinct. V4→V5 tests preserve incidents/users/organizations/memberships. Existing JPA validation remains enabled; V1–V4 are unchanged.

**CURRENT M11:** Append-only V6 adds immutable storage provider (old revisions LOCAL), preparation jobs and outbox records with composite tenant FKs, uniqueness, state/lease constraints and due/recovery indexes. Token leases and document-first row locks enforce concurrency; explicit JDBC transaction boundaries fence completion. V1–V5 are unchanged; a V5→V6 preservation/constraint test is included. No vector schema is added.
