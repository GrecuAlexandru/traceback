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

**Historical M9 baseline:** JWT-backed incident CRUD existed; RAG and MCP were planned at that checkpoint. Subsequent implementation notes below record their progression.


**M10 checkpoint:** Document/revision tenant ownership, immutable source IDs/checksums, membership checks before content reads and deletion tombstones are implemented. Future retrieval must exclude deleted documents and authorize sources before context assembly. No retrieval, LLM or MCP code is present.


**M12 checkpoint:** Authorized full-text/cosine/RRF retrieval, compatible local query embeddings, bounded real-source references, current/tombstone filtering and a synthetic real-model evaluation are implemented. Untrusted content remains inert. RAG context assembly, generated answers and MCP remain PLANNED; no such endpoints exist. See [M12](../M12_EXTRACTION_AND_RETRIEVAL.md).


**M13 checkpoint:** Private persistent conversations, durable/idempotent ordered turns, bounded local inference and assigned-source citation validation are implemented in `rag` with append-only V8. All supplied and inherited source dependencies are checked against current authorization/publication before generation, finalization and historical reads; invalid historical answers are redacted. No tools or MCP. See [ADR-0008](0008-private-conversations-and-validated-rag-evidence.md) and [M13](../M13_RAG_CHAT.md).


**CURRENT M14:** Disabled-by-default, loopback-only stateless MCP exposes six explicit read-only
tools through existing authorized services. A separate JWT chain requires the canonical MCP
resource audience while REST retains traceback-api. Original validated JWTs cross threads in
an immutable request context; no session confers identity. Exact source resolution retains all
M12/M13 live visibility predicates. Protected-resource metadata, strict limits, fixed-label
metrics and real isolated Keycloak/official-client tests are provided. M13 citation markers
reject unsupported dialects while preserving normal Markdown. Earlier implementation notes
above describe their historical checkpoints. See [ADR-0009](0009-stateless-read-only-mcp.md)
and [M14](../M14_SECURE_MCP.md) for version/OAuth limitations and actual verification.

**CURRENT M15:** CommonMark-aware citation policy closes remaining numbered-namespace
presentation gaps and invalid historical answers redact on read. Broader retrieval,
deterministic adversarial/RAG and bounded real-model evaluations remain separate from
authorization evidence. No model tool execution or permission cache is added. See
[M15](../M15_SECURITY_AND_EVALUATION.md) and ADR-0008 for policy and measured limitations.


**CURRENT M16:** M16 renders Markdown without raw HTML/images, resolves citations live, shows redaction/abstention/degraded search and clears organization-scoped client data. It never exposes tokens, runs model tools or changes retrieval/MCP authorization. See [ADR-0010](0010-nextjs-astryx-and-local-bff-sessions.md) and [M16](../M16_FRONTEND.md).
