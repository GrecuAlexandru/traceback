# Architecture Decision Records

ADRs record durable engineering trade-offs. They prevent later AI sessions from silently changing the project's architecture.

## Status meanings

- **Proposed:** under consideration; implementation must not assume approval.
- **Accepted:** agreed direction; may be only partially implemented.
- **Superseded:** replaced by a later ADR; retain for historical context.

## Decision index

| ADR | Title | Status |
| --- | --- | --- |
| [0001](0001-modular-monolith.md) | Modular monolith with separate runtime roles | Accepted |
| [0002](0002-identity-and-tenant-security.md) | OIDC identity and application-owned authorization | Accepted |
| [0003](0003-postgresql-flyway-and-pgvector.md) | PostgreSQL + Flyway, pgvector when needed | Accepted |
| [0004](0004-durable-ingestion-aws.md) | Durable document ingestion via AWS S3/SQS | Accepted; M11 local implementation |
| [0005](0005-rag-and-mcp-trust-boundaries.md) | Permission-aware retrieval and MCP tools | Accepted; implemented through local M14 |
| [0006](0006-ingestion-leases-and-storage-compatibility.md) | Token leases, explicit provider compatibility, generation retry | Accepted for M11 local implementation |
| [0007](0007-isolated-extraction-and-index-generations.md) | Isolated PDF extraction, local embeddings and atomic index generations | Accepted for M12 local implementation |
| [0008](0008-private-conversations-and-validated-rag-evidence.md) | Private conversations and validated RAG evidence | Accepted for M13 local implementation |

| [0009](0009-stateless-read-only-mcp.md) | Stateless read-only MCP with independent OAuth resource | Accepted for requested M14 local implementation |

## Creating a new ADR

Create `NNNN-short-title.md` with:

```markdown
# ADR-NNNN — Decision name

- Status: Proposed | Accepted | Superseded
- Date: YYYY-MM-DD

## Context
Why this decision is necessary.

## Decision
The chosen approach and scope.

## Alternatives considered
Meaningful competing options and why not chosen.

## Consequences
Benefits, trade-offs, risks, and follow-up tests.

## Implementation status
What actually exists today; what is still planned.
```

Don't rewrite accepted ADR history to hide a change. Propose a new ADR and link the one it supersedes.
