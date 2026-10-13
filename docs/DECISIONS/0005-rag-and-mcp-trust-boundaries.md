# ADR-0005 — Permission-aware RAG and MCP trust boundaries

- **Status:** Accepted (target architecture; implementation pending)
- **Date:** 2026-10-13

## Context

Traceback will search internal runbooks and incidents, generate answers with an LLM, and expose tools through MCP. Retrieved text and external tool output may be malicious or contain other tenants' confidential information.

## Proposed decision

Build retrieval around server-enforced organization and document permissions. Start with PostgreSQL keyword and pgvector retrieval, then evaluate hybrid ranking on a fixed dataset. Assemble LLM context only from authorized excerpts and validate output citations against real accessible source IDs. Return an explicit insufficient-evidence result where appropriate.

Expose read-only MCP tools through the same application services and authorization rules as REST. Authenticate MCP calls, validate arguments, restrict external MCP connections to approved servers, and treat retrieved/tool content as untrusted data, not system instructions.

## Alternatives considered

- **Ask the LLM to respect permissions in prompts:** not a security boundary.
- **Global retrieval followed by response filtering:** private material may already reach the model.
- **Unrestricted agents and write tools from day one:** excessive risk and hard to test.
- **Vector-only retrieval without evaluation:** misses exact technical identifiers and provides weak quality evidence.

## Consequences

- Security tests must include cross-tenant retrieval, source-ID guessing, poisoned documents, and tool misuse.
- Evaluate recall@k, citation validity, abstention behavior, latency, and cost; report actual data only.
- MCP and RAG must not become alternative authorization bypasses.

## Implementation status

**PLANNED only.** JWT-backed incident CRUD exists; no RAG or MCP implementation found in the verified baseline.


**CURRENT M10 foundation:** Document/revision tenant ownership, immutable source IDs/checksums, membership checks before content reads and deletion tombstones are implemented. Future retrieval must exclude deleted documents and authorize sources before context assembly. No retrieval, LLM or MCP code is present.
