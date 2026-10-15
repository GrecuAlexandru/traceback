# ADR-0008 — Private conversations and validated RAG evidence

- **Status:** Accepted for M13 local implementation; approved locally by the M14 request
- **Date:** 2026-10-14
- **Extends:** ADR-0001, ADR-0002, ADR-0003, ADR-0005, ADR-0007

## Decision

Extend the modular monolith with `rag`. Conversations belong to one organization and
are private to their creator's `(issuer, subject)` application identity. All persisted
membership roles may chat; ADMIN does not confer access to another creator's chat.
User-facing SQL independently checks ownership and live membership. Use V8 only;
source revisions, index generations and V1–V7 remain unchanged.

Use synchronous non-streaming HTTP with durable reservation and short transactions.
Lock the conversation only to reserve or finalize; never hold a transaction or lock
across retrieval provider I/O or chat inference. Allocate adjacent user/assistant sequence
numbers, a server turn ID and a mandatory client UUID idempotency key. One GENERATING
turn per conversation is enforced by a partial unique index. Keys replay terminal or
pending state without regenerating; changed text conflicts. Expired reservations become
FAILED on the next submission. A new key is an intentional new attempt. No chat queue
or M11 worker redesign is necessary at this scale.

Use only local Ollama through the existing Spring AI 2.0.1 dependency. Pin and verify the
installed model digest, completion capability and context capacity before transmitting
text and again after generation. No auto-pull or cloud provider exists. Explicitly deny
tool definitions/execution, and disable provider observation payloads. Resource limits
and a process-local admission semaphore bound calls. A timed-out uncooperative provider
keeps its capacity permit until it exits; it cannot commit a late answer.

Reuse M12 hybrid search. Assign `[S1]` etc. to bounded authoritative excerpts before
inference. System policy is separate from JSON-encoded untrusted question/history/source
data. Schema-constrained JSON output must select assigned references; the backend renders validated selected references as citation markers when needed. Missing, invented, malformed
or inaccessible references fail closed. Abstention is a backend-owned fixed response.
Reference validation proves provenance and current access, not semantic claim support.

Persist assistant content, sanitized generation metadata and source foreign keys; do not
persist internal prompts or duplicate raw context. Track every supplied source, including
uncited passages, plus inherited history dependencies. All must remain current and
published. Deleted/superseded revisions or replaced heads redact the entire historical
assistant response and all citations on every history, message, citation and replay read.
Redacted answers and their paired turn context never enter a later prompt. This deliberately
conservative policy can redact answers that did not actually depend on a changed passage.

## Alternatives and consequences

Shared chats and streaming increase authorization/partial-output complexity and are deferred.
Long database locks during inference obstruct deletion and exhaust connections. Automatic
retry of a whole turn risks duplicates; only an explicit transient provider status may
retry once within one reservation and deadline. Historical citations without live
revalidation would defeat deletion. Tracking only displayed citations misses uncited use
and transitive multi-turn content, so all admitted evidence is tracked.

Source validation uses short database snapshots before transmission, completion and return.
Revocation after an authorized snapshot cannot retract an already in-flight prompt or
response. No distributed instantaneous revocation guarantee is claimed. Privileged SQL
is outside application isolation; this is not database RLS. Soft deletion preserves private
rows indefinitely; physical erasure, expiry scheduling, distributed admission, broader
semantic evaluation and streaming remain future work. The API remains local.

## Implementation

See [M13](../M13_RAG_CHAT.md) for schema, API, actual model/configuration, limits,
verification, evaluation observations and operational recovery.


**M13 model follow-up:** The user approved qwen2.5:7b and 8 GiB/four-CPU local caps. Explicit batch 64, four threads, memory-mapped weights and a 120-second bound were needed for reliable observed CPU inference. The ten-case synthetic run and manual six-answer source audit passed; user acceptance remains pending. This does not change authorization, source visibility, the absence of tools or the need for broader evaluation.


**M14 citation follow-up:** The user approved M13 locally and requested an explicit marker
correction. M13 answer validation rejects unsupported provider/source citation dialects,
malformed/grouped/lowercase/unclosed S-number references and linked citation markers, while
preserving normal Markdown links, headings, [SRE]/[Service] and engineering array notation.
Exact-source SQL and the M13 evaluation dataset/results are unchanged. M14 read-only tools
are independent of chat generation; no agentic tool loop was added. See [M14](../M14_SECURE_MCP.md).

**M15 implementation note:** CommonMark AST parsing now separates literal code from visible
prose. Reserved H/DOC/SRC/REF/source/citation dialects, external citation links/definitions
and malformed S references fail safely; emphasis/inline HTML cannot split a label to bypass
validation. Structured selections must cover inline references, with safe backend rendering
of missing declared markers. Current authorization and citation-format consistency are
rechecked on history/message/citation/replay reads; invalid old answers redact without
rewriting stored rows. Policy metadata is `commonmark-sources-v1`. Syntactic provenance
does not establish semantic claim support. Expanded deterministic and bounded live evidence,
including earlier memory failures, is in [M15](../M15_SECURITY_AND_EVALUATION.md).


**CURRENT M16:** M16 preserves non-streaming durable reservation semantics: tab-local UUID-only replay, no automatic write retry/regeneration, pending-only visible polling, explicit stalled new attempts and live citation/history revalidation. No M13 persistence or inference change. See [ADR-0010](0010-nextjs-astryx-and-local-bff-sessions.md) and [M16](../M16_FRONTEND.md).
