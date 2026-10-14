# M13 — Persistent grounded RAG chat

**Status:** Implemented locally with the real-model synthetic smoke/quality checkpoint completed; user milestone review pending. M9A–M12 are approved for local development
by the M13 request. Only M13 is implemented here. No MCP, tools, frontend, cloud AI,
production deployment or changes to live authentication/persistent source data.

## Architecture and internal checkpoints

The `rag` feature extends the Java 21 / Spring Boot 4.1.1 modular monolith. Existing
Spring AI 2.0.1, PostgreSQL/Micrometer, trusted-human identity policy, database membership
authorization and M12 hybrid retrieval are reused. No new Java dependency is needed.
[ADR-0008](DECISIONS/0008-private-conversations-and-validated-rag-evidence.md) records the
ownership, persistence, inference and source visibility decisions.

Checkpoint A introduced V8, private CRUD and four PostgreSQL persistence/security tests.
B introduced the local provider and limit/digest/cloud checks. C added bounded context
and strict citation validation. D added durable turns, history, idempotency and failure
recovery. E exercises tenant/source races, malicious text, bounded concurrency, adapter
transport behavior, migration compatibility, reproducible evaluation and regression tests.
Exact final results appear below; intermediate compilation/path/retry failures were corrected.

A user turn follows:

1. Validate bounded input and require trusted human identity plus current database membership.
2. Lock the owned active conversation briefly. Recover expired turns, replay an existing
   key or reserve one new turn and two server-generated message IDs/sequence numbers.
3. Load a bounded recent history snapshot; revalidate every assistant's source dependencies.
   Exclude redacted/failed turns. Build a deterministic topic-aware query, without another LLM.
4. Call existing M12 hybrid search, whose SQL filters live tenant/membership/current revision/
   published generation before ranking. Keep bounded candidates; pure semantic candidates
   require configurable cosine similarity at least 0.5 by default. M12 keyword fallback is
   explicit and recorded if the embedding dependency is unavailable.
5. Deduplicate generation/chunk pairs, select bounded authoritative 600-code-point excerpts,
   and assign S references. No storage keys, filesystem paths, identity credentials or infrastructure
   configuration are loaded into context. Document titles, sections and content remain untrusted.
6. Recheck access and supplied/inherited sources, then call local inference outside transactions.
   Empty/below-threshold evidence skips inference and returns canonical insufficient evidence.
7. Parse strict schema-constrained status/answer/references JSON, validate selected and inline references against supplied evidence, deduplicate
   citations and reject malformed/uncited answers. Recheck access, conversation and all source
   dependencies in a new short transaction. Commit answer, evidence references and metadata atomically.
8. Resolve the response through a fresh authorized snapshot; return only accessible content/citations.

One retrieval pass and one successful answer-generation call occur per turn. An explicit HTTP
502/503 may retry inference once within the same deadline/reservation. No model query rewriting,
autonomous loops, shell/file/network tools or streaming exists.

## Database and retention

V8 is append-only. V1–V7 are unchanged; Hibernate remains `ddl-auto: validate`.

| Table | Behavior |
| --- | --- |
| `conversations` | Server UUID, organization, creator application-user FK, title, next sequence, created/updated timestamps and tombstone. Private owner/date index. |
| `conversation_turns` | Server UUID, composite conversation/tenant FK, client UUID key and normalized-question SHA-256, durable state/deadline, sanitized error/configuration/usage JSON and timestamps. Unique conversation/key; at most one GENERATING turn. |
| `conversation_messages` | Separate server UUIDs for USER and ASSISTANT, composite turn ownership, unique ordered sequence and role per turn. User text committed at reservation; assistant placeholder has no content until successful finalization. |
| `conversation_evidence` | Message ownership and exact tenant/document/revision/generation/chunk/configuration composite FK, backend reference and cited flag. Includes uncited and inherited dependencies; raw excerpts are resolved from authoritative current chunks rather than copied. |

Only the creator can read, rename, delete or continue a conversation, even when another
member is ADMIN. ADMIN, ENGINEER and VIEWER may create their own conversations. Membership
removal denies every subsequent request using the existing JWT. Known foreign IDs have
the same missing response as unknown IDs within an authorized organization.

Deleting a conversation tombstones it and fails a pending turn; no original source or message
is physically deleted. Rows remain privately retained indefinitely, with no restore or purge API.
User-authored question/title text remains user content; deletion cannot identify/remove source
text a user independently pasted into it. This feature is access invalidation, not data erasure.

Historical assistant messages are **REDACTED** if any supplied, uncited or inherited source
is deleted, superseded, not INDEXED or no longer the published head. Even reindexing the same
revision conservatively invalidates an answer from the replaced generation. Citation excerpts,
message reads and idempotent replays use the same policy. Redacted content never enters later
model history. Up to 64 inherited source dependencies are admitted, plus at most eight current
sources; older oversized dependency histories are excluded. The final budget may exclude more
history than the conservative dependency set, so redaction can exceed actual model use.

## REST contract

Base: `/api/v1/organizations/{organizationId}/conversations`. All endpoints use validated
JWTs and database authorization, return `Cache-Control: no-store`, and accept no client roles,
source IDs or model settings as authority.

| Method | Suffix | Contract |
| --- | --- | --- |
| POST | collection | `{"title":"Database investigation"}`; 201 with Location and conversation. Title 1..120 characters, trimmed, no controls. |
| GET | collection | `page=0&size=20`; size 1..100, page 0..10000; private items/count; created time DESC and UUID DESC. |
| GET | `/{id}` | Same pagination bounds; conversation and messages in sequence ASC, with total count, redaction and current citations. |
| PATCH | `/{id}` | `{"title":"Updated investigation"}`; 200. Concurrent renames use last committed write. |
| DELETE | `/{id}` | 204, tombstone and pending-turn invalidation; already missing/deleted IDs return generic 404. |
| POST | `/{id}/messages` | `{"idempotencyKey":"client-generated UUID","content":"Why HTTP 503?"}`; 200 terminal result, 202 if a duplicate key is still generating. |
| GET | `/{id}/messages/{messageId}` | Independently scoped message/status/citations read. |
| GET | `/{id}/messages/{messageId}/citations/{reference}` | Independently authorize ownership and current source; 404 for missing/stale reference. |

Turn responses include conversationId, userMessageId, assistantMessageId, answer, validated
citations, status, errorCode, createdAt and completedAt. Sources include reference, exact
document/revision/chunk/generation IDs, current title, section or PDF page range, and the
same bounded immutable excerpt supplied for generation. Future navigation can use existing
authorized document/revision content routes. Provider credentials, internal prompts, storage
keys/paths and dependency bookkeeping are excluded from public JSON.

401 comes from the resource server; 403 denies membership/human classification; 404 hides
foreign/other-owner/deleted IDs; 400 rejects malformed input/pagination; 409 rejects changed
idempotency text, simultaneous distinct turns, stale sources or expired finalization; 429 means
all local generation slots are occupied; sanitized dependency/invalid-output failures return 503.
Inspect history/replay for durable failure status after a first failing request.

## Model and local configuration

Separate Ollama chat service on `http://127.0.0.1:11436`; embedding remains on 11435.
Chat defaults to disabled. Model management NEVER pulls. The operator must set a nonzero
approved installed digest before enabling chat; tags/show verify digest, completion capability,
no remote-model fields and at least the configured context capacity before and after inference.
Endpoints are restricted to local HTTP names; redirects are disabled. The opt-in runtime has
`OLLAMA_NO_CLOUD=1`, one loaded model, one parallel inference, four CPUs, 8 GiB configured memory and
no additional swap. Existing embedding/service volumes are preserved.

The selected approved local model is `qwen2.5:7b`, 7.6B parameters, Q4_K_M,
4,683,087,332 model bytes; manifest metadata reports a 32,768-token context. Installed digest:

```text
sha256:845dbda0ea48ed749caafd9e6037047aa19acfcfd82e704d7ca97d631a0b697e
```

The host has 24 GiB RAM, but Docker's Linux VM reports about 7.75 GiB total memory.
The initial default inference batch caused OOM failures. Explicit prefill batch
size 64, four CPU threads, memory-mapped weights and no memory locking resolved the
observed failures without changing Docker's VM allocation or other services. Active
samples were about 4.62–4.67 GiB; these are spot measurements, not a certified peak.
The runtime stays within the approved 8 GiB/four-CPU caps and unloads after each request.
The 1.5B model and its failed quality evidence are preserved separately.

The configured 8,192-token context supports conservative byte budgets. CPU inference is
slow: final single-turn case latencies ranged 51.8–96.6 seconds (median 60.7), so the
bounded deadline is 120 seconds. The follow-up case took 146.5 seconds including its setup
question and follow-up, each separately bounded. Larger model size is not an accuracy
guarantee; actual source/claim review appears below. Chat remains disabled until explicitly
enabled with the pinned digest. No chat/embedding model interchange is permitted.

| Environment setting | Default / bound |
| --- | --- |
| CHAT_ENABLED | false; true requires valid nonzero CHAT_MODEL_DIGEST |
| CHAT_MODEL / CHAT_ENDPOINT | qwen2.5:7b / http://127.0.0.1:11436 |
| CHAT_CONTEXT_TOKENS | 8192; 4096..32768; installed capacity must cover it |
| CHAT_OUTPUT_TOKENS | 512; 64..2048 |
| CHAT_TEMPERATURE | 0.0; finite 0..0.5 |
| CHAT_TIMEOUT_SECONDS | 120; 1..120; total inference deadline includes compatibility checks/retry |
| CHAT_MAX_CONCURRENT | 2; 1..4 per API process; no queue of unbounded model tasks |
| CHAT_MAX_QUESTION_BYTES | 1000; configurable 1..4000 UTF-8 bytes |
| CHAT_PROMPT_BYTES | 6000; 2000..16000; prompt bytes + output tokens + 1024 reserve must fit configured context |
| CHAT_EVIDENCE_BYTES | 3000; 1..8000 serialized JSON bytes |
| CHAT_HISTORY_BYTES / CHAT_HISTORY_MESSAGES | 1500 / 8; 0..4000 / 0..16 |
| CHAT_RETRIEVAL_CHUNKS | 4; 1..8 |
| CHAT_BATCH_SIZE | 64; 1..128; bounds inference prefill working memory |
| CHAT_THREADS | 4; 1..4; CPU inference threads, also constrained by the Compose CPU cap |
| CHAT_MIN_SEMANTIC_SIMILARITY | 0.5; 0..1, applied to candidates without a keyword rank |

System policy plus JSON payload uses a conservative one-token-per-UTF-8-byte ceiling with
1,024 tokens reserved for template overhead and a separate output allowance. This is not
an exact tokenizer; no prompt truncation is requested. Context escaping/Unicode overhead is
measured after JSON serialization. Provider bodies are capped at 64 KiB, generated JSON at
8,000 bytes and answer content at 6,000 bytes. Retrieval snippets remain M12's 600 code points.
History/query/source limits prevent growth with total conversation length. Long conversations
retain only bounded recent authorized context, so older details can be forgotten.

Approved local setup (do not auto-run for new users without model-download approval):

```bash
# Repository root. The existing pinned Ollama runtime image is reused.
docker volume create traceback_m13_models
docker compose -f compose.yaml -f compose.m13.yaml --profile m13 up -d --no-deps chat-ollama
docker compose -f compose.yaml -f compose.m13.yaml --profile m13 exec -T chat-ollama ollama pull qwen2.5:7b
export CHAT_ENABLED=true
export CHAT_MODEL_DIGEST=sha256:845dbda0ea48ed749caafd9e6037047aa19acfcfd82e704d7ca97d631a0b697e
# Retain M12's approved embedding/indexing configuration if semantic retrieval is needed.
# Starting the API normally applies V8; persistent database migration was not done by this task.
```

Do not mutate a pinned model while requests are running. A new model requires explicit
compatibility/configuration review and a new digest. No paid providers or external web browsing
were used. The local inference model has no per-call API charges.

## Failure, concurrency and recovery

Reservation, inference and finalization are separate phases. Database work uses five-second
transactions. Retrieval may take M12's existing bounded embedding timeout separately from
the chat deadline; turn leases are chat timeout plus 45 seconds to cover retrieval/finalization.
A partial unique index and short conversation locks serialize reservation. User and assistant
messages always occupy adjacent monotonic slots; failures retain empty assistant placeholders.

The same key and normalized text always select the same messages. Same key while pending
returns 202; a distinct key while active returns 409. Failed/expired keys remain terminal;
use a new key to intentionally retry. No automatic entire-turn regeneration occurs. Recovery
is lazy on the next submission to that conversation, with server database time, not a new job
framework; a GET alone may show GENERATING until this reconciliation occurs.

Model timeouts cancel the future and fail the turn. A provider ignoring interruption retains
its semaphore slot until it returns; output cannot persist after FAILED/deadline/tombstone.
This bounds resource growth at the cost of temporarily rejecting requests. Admission also
covers retrieval/preparation. The Ollama runtime may independently queue within its bounded
parallel setting. Client disconnects do not constitute reliable cancellation; server state
remains authoritative and retrying the key observes that state.

Unavailable or malformed models, missing/invalid citations and source races yield no successful
assistant content. Empty evidence abstains with no model call. A finalization database failure
rolls answer/evidence back; a separate compensation transaction marks the reservation FAILED.
If the database remains unavailable, the reservation expires and reconciles later. An ambiguous
commit acknowledgement preserves a committed successful turn; compensation only changes
GENERATING rows. Idempotent replay resolves the durable result.

Membership, conversation deletion, document tombstones, supersession and head replacement
are rechecked before model transmission, finalization and response. These are short authorized
snapshots. A mutation after the last snapshot cannot retract content already authorized/in
flight. Database RLS, instantaneous distributed revocation, distributed admission, rate billing,
SSE, physical purge and automatic crashed-turn scheduling are not implemented.

## Trust boundary and groundedness

Only the fixed application system policy has instruction authority. Question/history/passages
are JSON data, with role delimiters escaped. Fake administrator/system claims, fabricated
references and commands in documents do not alter deterministic organization SQL or grant
tools. The provider has no executable tool definitions and rejects tool calls. No secrets are
added to prompts; authorized documents themselves can contain user-uploaded sensitive text,
so this is not a secret-detection/DLP system. Provider observation payloads are disabled, and
application metrics/errors never log raw conversations/content/tokens/provider bodies.

ANSWERED requires at least one valid model-selected assigned reference (in the references array or inline). The backend renders validated selected references absent from prose as bracketed markers. It never invents or selects a source on the model’s behalf. Backend parsing proves exact
provenance and current visibility only. It does not prove every claim is supported. A deterministic
test explicitly accepts a syntactically cited unsupported claim to demonstrate that distinction.
The local model can still hallucinate, misread conflicting evidence or obey malicious text within
its output. Code-enforced authorization and absence of tools remain the security boundary.
Model abstention content is replaced with the fixed insufficient-evidence response; empty or
below-threshold retrieval is a deterministic abstention. The similarity threshold is a safe initial
heuristic, not calibrated production relevance evidence. The Ollama JSON schema constrains status and reference shape/namespace, while backend validation also checks actual supplied IDs and any inline markers. Strict JSON/citation validation may
reject useful answers from small models instead of repairing them with another model call.

## Observability and troubleshooting

Micrometer exposes fixed, low-cardinality measurements: generation outcomes, active/admission
rejection count, model/retrieval timers, context bytes, evidence count, invalid citations, timeout/
provider/recovery failures and available input/output token summaries. Model/configuration (including batch size and CPU threads),
effective retrieval/degradation, budgets, supplied counts and sanitized usage are persisted per
successful turn. Only health is publicly exposed by existing Actuator configuration.

CHAT_DISABLED: configure an explicitly approved pinned model. MODEL_INCOMPATIBLE: check the
local tags/show digest, completion capability, context capacity and absence of remote fields.
MODEL_UNAVAILABLE: inspect local service availability/resources without enabling request-body
logging. MODEL_TIMEOUT: inspect resource usage and reduce context/output or explicitly adjust
the bounded deadline. GENERATION_CAPACITY: wait for active local work; do not spawn unlimited
threads. INVALID/MISSING_CITATION: inspect only synthetic evaluations; private prompts are not
logged. SOURCE_CHANGED/REDACTED: current documents/index heads changed; start a fresh turn
using live evidence. GENERATION_EXPIRED: inspect durable state, then deliberately use a new
key. Database outage: restore connectivity; lazy reconciliation fences prior reservations.

## Evaluation and verification

`evaluations/m13/dataset.json` is a frozen synthetic corpus of six engineering/attack documents
and ten direct, exact-code, synthesis, conflicting-source, summary, multi-turn, absent/irrelevant,
prompt-injection and foreign-information queries. `RagModelEvaluation` explicitly runs real
M12 embeddings, ingestion/publication, hybrid retrieval and local chat against disposable
pgvector PostgreSQL, with synthetic principals and a mocked JWT decoder. The foreign tenant
contains a distinct recovery-code sentinel. M12 dataset/results are unchanged.

```bash
# backend/, approved embedding and chat services running:
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=RagModelEvaluation \
  -Dtraceback.chat.model=qwen2.5:7b \
  -Dtraceback.chat.digest=sha256:845dbda0ea48ed749caafd9e6037047aa19acfcfd82e704d7ca97d631a0b697e \
  -Dtraceback.chat.timeout-seconds=120 test
# Deterministic M13 suite independently, no model download or running inference required:
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw \
  -Dtest=ConversationIntegrationTest,ChatIntegrationTest,ChatMigrationIntegrationTest,RagContextTest,OllamaChatProviderTest test
```

Saved observations distinguish supplied-evidence recall, expected-status/abstention behavior,
exact expected-term matching, rejected generations and foreign leakage. Expected-term matching
is a simple reproducible oracle, not semantic groundedness. Saved answers and source excerpts
permit separate manual citation-support/claim review. Real-model prompt injection and contextual
accuracy are assessed from those actual observations, never inferred from mocks. Deterministic
SQL tests separately exercise deletion, supersession, revoked membership, guessed IDs, inherited/
uncited provenance, injection trust boundaries, fabricated references and concurrency failures.

Final commands, smoke outcomes and manual review are recorded after completed runs below.

### Historical 1.5B model quality findings

The historical 1.5B conditional-schema run passed the end-to-end smoke assertion that at least one
real answer was generated, but **it did not pass a useful answer-quality gate**. Ten cases
produced two ANSWERED responses, seven canonical abstentions (including false abstentions
on supported questions), and one rejected fabricated-citation generation. Supplied-evidence
recall was 1.00 across seven labeled source cases; expected status matched 5/10; expected
terms matched 2/6; unsupported-question abstention matched 3/4; foreign content leakage was
zero. The two accepted answers returned five real/current citation references, all passing
backend validity checks. This does not establish semantic citation support.

The separate [1.5B manual claim review](../evaluations/m13/claim-review-1.5b.json) compared every
material claim to returned cited excerpts. Strict complete citation support was **0/2**:
the synthesis omitted the rollback citation and included an attack-fixture citation;
the conflict answer added an unsupported organizational-approval claim. Therefore the
1.5B model is not a recommended quality baseline. Real multi-turn correctness was not established with that model despite deterministic application tests passing.

The initial inline-only run rejected all ten turns; later inline/structured attempts and
the successful single-question diagnostic are preserved beside final results. The initial
report did not measure supplied-evidence recall before rejected generations; its old zero
placeholder was corrected to null without changing observations. Intermediate protocol
results must not be treated as final production quality. The dataset is unchanged throughout.

The user separately approved the 7B download and 8 GiB/four-CPU runtime caps on
2026-10-14. The model is installed; both model weights are retained in the same private
local model volume. Compose caps remain explicit via CHAT_OLLAMA_MEMORY/CHAT_OLLAMA_CPUS,
now defaulting to the approved 8 GiB/four CPUs. No auto-pull or cloud inference is enabled.

### Final 7B evaluation and claim review

The same frozen dataset and prompt/schema were evaluated with the real 7B model, real
M12 embeddings, batch size 64, four threads and a 120-second generation deadline:

| Measure | Actual observation |
| --- | --- |
| Cases | 10: six supported, four expected abstentions |
| Expected status | 10/10 |
| Expected technical terms | 6/6 supported cases |
| Supplied-evidence recall | 1.00 across seven labeled source cases, including the attack fixture |
| Insufficient-evidence behavior | 4/4 absent, irrelevant, injection and foreign-information cases abstained |
| Failed cases | 0 |
| Foreign leakage | 0 |
| Valid returned citations | 9/9: real, provided, current authorized source references |
| Strict complete semantic citation support | 6/6 accepted answers in a separate manual source audit |
| Contextual follow-up | Correctly cited pool reduction to 20 and closing leaked connections |

[Final observations](../evaluations/m13/results.json) record model/digest/configuration,
dataset/prompt hashes, raw synthetic outputs, selected sources, status and latency.
[Manual claim review](../evaluations/m13/claim-review.json) is bound to the report hash and
compares each material claim to returned cited passages. Synthesis cited the database,
rollback and pool-policy sources correctly. The conflicting-limit answer reported 20 versus
10 without inventing organizational approval or a resolution. No attack-fixture citation was
returned as engineering support. This establishes the local synthetic checkpoint only;
six supported answers and one follow-up/injection case do not establish general groundedness,
perfect injection resistance or production accuracy.

Historical 1.5B results/review, the 7B OOM attempt and interrupted 60-second-deadline run
are preserved separately. The 60-second run admitted direct/exact-code answers but reached
the bound on synthesis/conflict; its incomplete observations are not full benchmark metrics.
The final ten-case run completed in 12:25. Dataset and M12 results are unchanged. Code still
enforces authorization, source visibility and reference validity independently of model quality.

### Final verification and file review

Commands ran with Java 21.0.12.1 on 2026-10-14. Maven commands ran in `backend/`.

| Exact command | Actual result |
| --- | --- |
| `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=ConversationIntegrationTest test` | PASS: checkpoint A, four tests. |
| `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=ConversationIntegrationTest,ChatIntegrationTest,ChatMigrationIntegrationTest,RagContextTest,OllamaChatProviderTest test` | Initial checkpoint: 42 M13 tests passed, no failures/errors/skips, 20.616 seconds; the full final suite also runs the two follow-up tests. |
| `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw clean test` | PASS on selected 7B/batch/thread defaults and expanded citation tests: 196 tests, no failures/errors/skips, 1:04. |
| `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw verify` | PASS: 196 tests, no failures/errors/skips; executable application JAR packaged, 1:01. |
| `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=RagModelEvaluation -Dtraceback.chat.digest=sha256:65ec06548149b04c096a120e4a6da9d4017ea809c91734ea5631e89f96ddc57b test` | Historical 1.5B-default run: smoke assertion passed, one test, 1:39; quality gate failed as documented above. Reproduction now also requires `-Dtraceback.chat.model=qwen2.5:1.5b`. |
| `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=RagModelEvaluation -Dtraceback.chat.model=qwen2.5:7b -Dtraceback.chat.digest=sha256:845dbda0ea48ed749caafd9e6037047aa19acfcfd82e704d7ca97d631a0b697e -Dtraceback.chat.timeout-seconds=120 test` | PASS: one opt-in test, no failures/errors/skips, 12:25; all ten expected outcomes and manual six-answer support review passed. |
| `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw test-compile` | PASS: updated opt-in timestamp/reporting code compiled, 3.171 seconds; no tests executed by this compile-only command. |
| `docker compose config --quiet` (root) | PASS. |
| `docker compose -f compose.yaml -f compose.m13.yaml --profile m13 config --quiet` (root) | PASS. |
| `docker compose -f compose.yaml -f compose.m12.yaml -f compose.m13.yaml --profile m12 --profile m13 config --quiet` (root) | PASS. |
| `docker compose -f compose.yaml -f compose.m12.yaml -f compose.m13.yaml --profile m12 --profile m13 ps` (root) | Existing PostgreSQL/Keycloak/LocalStack/embedding services and approved chat runtime running, all published ports on loopback. |
| `git diff --check` (root) | PASS. |
| `git diff --exit-code -- backend/src/main/resources/db/migration/V{1..7}*.sql backend/src/main/java/dev/traceback/security/SecurityConfig.java evaluations/m12` (root) | PASS: historical migrations, JWT policy and M12 evaluation artifacts unchanged. |

The updated default suite includes the existing 152 tests plus 44 M13 tests. Real inference is
explicit opt-in, not a skipped default test. Provider tests use synthetic local HTTP servers;
integration tests use disposable pgvector PostgreSQL and existing temporary private storage.
Full regression includes previous documents/incidents/search and disposable LocalStack ingestion.

Changed files: new `rag` package (provider/settings/context, repositories/services/controller/error
handling), V8, six test/evaluation classes, `compose.m13.yaml`, application chat configuration (selected 7B model/120-second bound plus explicit batch/thread limits),
M13 dataset/observations/claim review, architecture/roadmap/ADR implementation notes and ADR-0008. Follow-up tests verify actual provider batch/thread/window/output/mmap settings and reject overlong fabricated citation markers. Evaluation dates are generated from run time rather than hard-coded for future executions.
M12 SearchRepository gains an exact independently authorized source resolver; its existing
ranking SQL/engine is unchanged. The V6→V7 migration test is pinned to V7 to preserve its
historical one-migration assertion, with a separate V7→V8 preservation test added.

Review covered bound SQL parameters, tenant/owner/membership predicates, composite evidence
FKs, reservation/finalization rollback, timeout and stale-turn fencing, deduplicated references,
uncited/transitive provenance, fresh historical/source authorization, fixed error/metric labels,
no tool definitions/execution, local-only digest/capability/context checks, HTTP body/deadline
bounds, inactive inference defaults and scope. A model response with valid references can still
contain unsupported claims; this is documented, tested and visible in actual evaluation findings.
Real Keycloak cryptography was not repeated because authentication policy is unchanged; synthetic
principals establish application authorization only.

Intermediate development issues included working-directory mistakes in file-writing commands,
a shadowed Java package name, wrapped Spring AI inference-status exceptions, and invalid initial
Compose environment-default syntax. These were corrected; final checks above passed. Initial real
model citation omissions/fabrications remain saved as quality evidence. The first 7B/default-batch attempt failed with OOM; batch 64 resolved observed memory failures. The 60-second benchmark was interrupted after deadline failures, with only completed partial observations retained. The final 120-second benchmark passed all ten cases. No private application
prompts/provider bodies are logged by default; raw outputs saved here are exclusively synthetic
opt-in evaluation material.

No persistent Compose database was migrated, source data erased, volume reset, live authentication
modified, cloud inference enabled, paid resources created, or code committed/pushed/merged/deployed.
The approved public 1.5B and 7B models occupy the separate `traceback_m13_models` volume; the idle runtime remains local. Model downloads and the 8 GiB/four-CPU cap were explicitly approved. No VM memory allocation or other service resources were changed.

**Next checkpoint:** User review of M13 implementation and the successful 7B synthetic evidence. Do not begin M14 without approval.
