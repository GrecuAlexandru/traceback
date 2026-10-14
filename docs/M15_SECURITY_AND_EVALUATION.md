# M15 — Security hardening, evaluation and operational readiness

Status: implemented locally; **M15 review pending** on `codex/m15-security-evaluation`,
2026-10-14. M9A–M14 are approved for local
development by the M15 request. No production exposure or security certification is implied.

## Checkpoint A: audit and risk register

Before changes: clean working tree on `codex/m13-rag-chat`; inspected repository modules,
configuration, migrations, ADRs 0001–0009, reports M9A–M14 and test infrastructure.
Independent static baseline and focused storage/worker review supplement the parent review.
Baseline command (backend/, Java 21): `./mvnw clean test`: **221 tests, zero failures,
errors or skips**, 1:24. Docker and approved local embedding/chat runtimes are available.

| ID / component | Threat or failure | Existing control | Remaining risk / severity / likelihood | Mitigation | Verification |
| --- | --- | --- | --- | --- | --- |
| C1 / citation presentation | Model invents `[H1]`, `[DOC42]` or external footnotes | Assigned source IDs, live source SQL, some marker validation | Confirmed integrity defect; medium / high for model output; no added read authority | Markdown-aware prose grammar, code exclusions, consistent declared references | Parser regressions and durable chat failure tests |
| R1 / REST incident input | Member submits huge description, later amplified by list reads | Human/membership/write authorization, title and page limits; multipart cap | Confirmed unbounded input; medium / medium; local authenticated writer required | 128 KiB REST body cap before deserialization; 20,000 UTF-16-unit new description cap | Known/unknown-length body tests; HTTP/service DTO rejection; existing large data stays readable |
| T1 / all tenant paths | Known foreign IDs, stale membership or source lifecycle races | Service authorization plus identity/tenant SQL, composite FKs, current published heads | High impact if regressed; no bypass identified in audit | Preserve controls; extend adversarial/concurrent tests | REST/MCP real filters and service/SQL regression suite |
| W1 / ingestion/indexing | Crash, duplicate delivery, stale lease or partial publication | Persisted event equality, token leases, outbox, checkpoints, atomic head publication | Recovery timing/operator dependence; no demonstrated integrity defect | Reuse existing failure fixtures; add missing controlled dependency faults | Disposable PostgreSQL/LocalStack and injected failures |
| E1 / AI quality evidence | Tiny easy corpus overstates accuracy | M12 ten queries and M13 ten questions, retained results | Evidence gap, not an authorization flaw | Larger graded corpus; deterministic and live results separated | Recall/MRR/nDCG, independent manual claim audit, leakage sentinels |
| P1 / publication | Hundreds of separate vector inserts exceed transaction budget | 512 chunk cap, 10-second transaction, final lease fence | Unmeasured performance risk; optimization optional | Measure near-limit 768-dimensional publication before changing batching | Repeated disposable-dataset timings and SQL plans |
| O1 / operational gates | Readiness/CI/scanner gaps conceal faults | Health-only Actuator, fixed metric labels, durable state gauges | Operational gap | Explicit readiness policy, reproducible CI/scans and troubleshooting | Configuration and failure tests; retained scanner outcomes |

Mandatory corrections are C1 and bounded REST input R1. T1/W1 are controls to verify,
not claimed vulnerabilities. P1 permits an optimization only if measurement justifies it.
No critical/high confirmed authorization flaw has been identified at this checkpoint.

## Internal checkpoints

A: source audit/risk register and baseline. B: citations, input limits and security regressions.
C: controlled recovery and stale-work tests. D: expanded retrieval/RAG/adversarial evaluation.
E: measured data paths, dependency/configuration review, readiness/metrics and CI.
F: full clean test/verify, separate live subset, Compose/diff review and final evidence.
No schema change, live Keycloak change or persistent database operation is planned.

Subsequent sections record executed results only; pending work is not completion evidence.

## Corrections and threat-model updates

**C1:** `CitationPolicy` uses CommonMark 0.25.1's AST, rather than matching the entire raw
answer as prose. Inline/fenced/indented code, including code nested in lists/quotes, is
literal. Emphasis and inline HTML cannot split a reserved visible label to evade checks.
Raw HTML blocks are outside the answer format and fail closed. This is a citation policy,
not a future frontend HTML sanitizer.

Only exact assigned bare `[S1]` etc. in prose can declare evidence. H/DOC/SRC/REF/source/
citation namespaces, malformed/grouped/unclosed identifiers, citation-shaped external
links, reference definitions, footnotes and provider glyph dialects fail safely. Ordinary
Markdown links, `[SRE]`, `[HTTP503]`, `[RFC9110]`, array notation and literal code remain
unchanged. An inline-only source selection remains supported for compatibility. A nonempty
structured selection must contain every inline selection; declared sources missing from
prose are rendered by the backend. Markers are placed before trailing code blocks when
needed so they cannot disappear inside an unclosed fence. Persisted cited references and
visible prose must agree on subsequent history/message/citation/replay reads; historical
pre-M15 invalid markers cause conservative redaction without rewriting stored content.

Source SQL/current-head/membership checks still run before inference, at completion and
on response/history. All admitted uncited and inherited evidence remains a revocation
dependency. Format/provenance validation **does not establish semantic claim support**.
The deterministic unsupported-claim test deliberately remains as counterevidence to any
such claim. Metadata records `citationPolicy=commonmark-sources-v1`; prompt and selected
model are unchanged. Ordinary URLs remain ordinary links, not backend-backed citations.

**R1:** Authenticated REST mutation bodies are capped at 128 KiB before deserialization,
including unknown/chunked lengths. The guard belongs to the REST security chain and does
not select by raw URI, so encoded path aliases cannot bypass it. Multipart retains its
existing separate cap. New supplied incident descriptions are limited to 20,000 UTF-16
units at HTTP and validated service entry points. Existing large descriptions remain
readable and title/status-only changes preserve them; no migration/truncation is performed.
Historical large-row read amplification remains an administrative/deployment limitation.

**Authentication policy gap:** An actual-filter test with a controlled local issuer
confirmed the prior REST decoder accepted a correctly signed, issuer/audience-matched JWT
without `exp`. Keycloak normally issues expiring tokens, so this is an issuer-policy
defense-in-depth gap, not a demonstrated token forgery or cross-tenant bypass. REST now
preserves configured issuer discovery/signature algorithms/audiences while requiring a
present, unexpired expiry, matching MCP's strict expiry policy. Default Spring not-before
validation retains its 60-second clock tolerance. Real-filter regressions vary signatures,
issuer, REST/MCP audiences, expiry, missing expiry, future not-before and malformed/missing
tokens. Human classification and current PostgreSQL membership remain separate from JWT
validity. No live Keycloak client, realm, credential or mapper changed.

**Dependencies:** Compatible Jackson BOM 3.1.7 and Tomcat 11.0.26 patches address confirmed
version matches. Boot 4.1.1, Spring AI 2.0.1, AWS SDK 2.55.14 and MCP SDK 2.0.0 remain on
their original release lines. The Maven 3.10.0 wrapper distribution is now SHA-256 pinned,
after checking its downloaded bytes against the publisher's SHA-512. No new ADR is needed:
these corrections implement existing trust/resource boundaries rather than redesign them.

Runtime assumptions remain explicit: local-only listeners; trusted host/Docker/model/
emulator administration; cgroup v2 enforcement for PDFs; no database RLS; no separate
API/worker OS or database principals provisioned here. Worker read-only storage is an
adapter control, with OS mounts/IAM isolation still future deployment work. Physical
purge, quotas, distributed admission and instantaneous revocation remain unimplemented.

## Security and failure test matrix

These are existing controls re-executed plus focused M15 additions, not one new test for
every checklist row. M15 adds no write-capable MCP or model tools.

| Boundary/scenario | Executable evidence |
| --- | --- |
| Signatures, issuer, audiences, exp/nbf, missing/malformed credentials, human/machine distinction | `RestJwtValidationIntegrationTest`; `McpJwtValidationTest`; real Keycloak `McpIntegrationTest` |
| Membership/RBAC, known foreign organizations/incidents, revocation/downgrade, direct service/SQL denials | `OrganizationApiIntegrationTest`, `IncidentApiIntegrationTest` |
| Known foreign document/revision IDs; title/revision/download/write roles; immutable history/tombstones | `DocumentApiIntegrationTest`, migration tests |
| Known foreign revision/generation/chunk IDs; exact sources; retired aliases; reused session across 24 alternating identities | Real HTTP/Keycloak/SDK `McpIntegrationTest` |
| Search contamination; deletion/supersession/revocation during query embeddings; incompatible/unpublished heads | `IndexIntegrationTest`, expanded retrieval corpus mirrored into a second tenant |
| Foreign conversations/messages; owner-only chats; deletion during inference; revoked membership; stale sources | `ChatIntegrationTest`, `ConversationIntegrationTest` |
| Historical deletion/supersession/head replacement, uncited/inherited sources, old unsupported marker redaction | `ChatIntegrationTest` history/message/citation/replay cases |
| Fabricated/linked/malformed markers, escaped/nested code, Markdown/engineering notation, structured disagreement | `RagContextTest`; durable failed-turn assertions |
| Storage traversal, symlinks, opaque keys, permissions, limits, hashes, missing/corrupt bytes and incomplete upload cleanup | `LocalDocumentStorageTest`, `DocumentApiIntegrationTest`, `DurableIngestionIntegrationTest` |
| S3/SQS failure; lost delivery; duplicate/out-of-order/forged messages; dispatcher send-before-commit crash | `DurableIngestionIntegrationTest`, disposable LocalStack, real SDK/queue/DLQ |
| Worker crash-state lease recovery; stale token/heartbeat/deadline; deleted/superseded processing | `DurableIngestionIntegrationTest`, `IndexIntegrationTest` |
| Partial embeddings, unavailable models, extraction checkpoint reuse, index insert/publication rollback | `IndexIntegrationTest`, `OllamaProviderTest`, `OllamaChatProviderTest` |
| Actual deferred database **commit** failure, no partial chunks/head, successful later retry | M15 `IndexIntegrationTest.deferredDatabaseCommitFailureRollsBackPublishedHeadAndRecovers` |
| PostgreSQL paused/unavailable: readiness fails, liveness stays healthy, readiness recovers | M15 `RestJwtValidationIntegrationTest.postgresOutageMakesReadinessFailWithoutFailingLivenessAndRecovers` |
| Missing/unenforced PDF isolation, parser timeout/cleanup and content/page/text/structure limits | `ExtractionTest`; actual disposable isolated parser |
| Chat deadline, interruption-resistant capacity retention, failed commit, idempotent pending/success/failure replay | `ChatIntegrationTest`, `OllamaChatProviderTest` |
| MCP body/argument/result/rate/deadline/admission bounds and no late output | `McpBoundaryTest`, `McpToolLimitTest`, `McpIntegrationTest` |
| REST known/chunked/encoded-alias bodies; oversized incident writes and legacy read compatibility | M15 real-filter MCP fixture and incident service/API regressions |

Crash tests inject durable abandoned claims/send-before-delivery states and expire leases
in disposable rows. A separately executable scheduled worker and real SQS/DLQ are also
exercised. This is not a hardware power-loss, real AWS durability or arbitrary network-chaos
certification. Source data and persistent Compose databases/volumes are never reset.

## Evaluation methodology

M12 and M13 datasets/results are preserved byte-for-byte. M15 results live separately.
`retrieval-dataset.json` freezes **39 documents and 78 queries** across 13 engineering
topics. Every topic has current guidance, a similar symptom with another cause and an
explicitly archived procedure. Queries include exact identifiers/stack frames, paraphrases,
confounders, synthesis, historical and ambiguous questions. Grades are authored before
measurement: 3 directly answers; 2 relevant to an intentionally ambiguous symptom.
Document-level ranking deduplicates chunks before Recall@5/@10, MRR@10 and nDCG@10.
The identical foreign corpus tests contamination. Sixteen queries are development, 62
evaluation; no retrieval tuning was performed and RRF k=60 is unchanged.

Default `M15RetrievalEvaluationTest` uses deterministic lexical-hash fixture vectors and
real PostgreSQL/full pipeline SQL. It verifies application contracts, not semantic model
quality. `-Dtraceback.evaluation.live-embeddings=true` explicitly selects the installed
pinned nomic v1.5 model. A model outage must fail this evaluation rather than silently
degrade. One warm-up per mode is excluded; latency includes service authorization,
retrieval and query embedding. No quality threshold was chosen after observing scores.

`rag-dataset.json` expands to **24 engineering questions**, with factual, exact-code,
multi-source, ordered procedure, multi-turn, conflict, ambiguity, absent-evidence, injection
and uncertainty cases. Deterministic scripted outputs test persistence, canonical
abstention and source/citation visibility. They deliberately leave groundedness,
unsupported claims and semantic citation support unmeasured. Lifecycle/revocation cases
are covered by the separate real-database regression matrix above.

`adversarial-dataset.json` has **12 controlled attacks** across Markdown, plain text,
actual PDF extraction, runbooks, excerpts, incidents and history: role/delimiter spoofing,
foreign IDs, fake evidence, credential exfiltration, unauthorized tools, abstention override,
instruction flooding, hidden Markdown, conflicts and follow-up contamination. Scripted
hostile outputs exercise the backend's rejection independently of LLM obedience. Incident
descriptions are stored/read as inert incident data; incident text is not an indexed RAG
source in the current product and does not enter evidence. No tool execution exists.

`M15RagModelEvaluation` is opt-in, at most six selected cases per invocation, default five:
direct, exact code, conflicting sources, injection and foreign information. Each turn is
bounded to 120 seconds and uses only the pinned Qwen2.5:7B plus real local embeddings.
It saves actual outputs, citations, evidence recall, statuses and latency. Term matching
is diagnostic only; a separate human claim audit must assess supported answers, unsupported
claims, citation support and coverage. Failures are not abstentions or supported answers.

## Operational behavior and troubleshooting

Only Actuator health is HTTP-exposed and details stay hidden. API readiness now includes
PostgreSQL; liveness remains independent of dependencies. Worker readiness additionally
includes configured S3/SQS. Ollama failure degrades hybrid retrieval explicitly to keyword,
makes semantic-only unavailable, and durably fails chat turns. It does not restart an
otherwise useful metadata/incident API. SQS outages retain outbox work; S3 failure produces
bounded storage failures/retries. Missing PDF isolation fails closed; TXT/Markdown remains
independent. The Hikari acquire timeout is three seconds, validation one second. No
permission cache or distributed limit is introduced.

Micrometer adds REST authentication/filter authorization denials, fixed-mode search
success/degradation/failure counts and duration, and failed index-publication counts.
Existing HTTP request status/latency, MCP calls/limits/timeouts, chat retrieval/model/outcome/
capacity, outbox pending/failure/expired lease and indexing state/visibility/duration metrics
remain. Business membership denials are visible through HTTP 403 status; the new security
filter counter does not claim to count controller-level business exceptions. Metric tags
contain only fixed boundary/reason/mode/outcome values, never IDs, queries, tokens or text.
Metrics/timers reset on process restart; persisted jobs/outbox remain authoritative.

| Symptom | Inspect and recover safely |
| --- | --- |
| Readiness 503, liveness 200 | Check PostgreSQL connectivity/capacity and sanitized pool/DB errors. Restore DB; do not loop-restart due to optional inference. |
| Document runtime remains unavailable after losing advisory-lock connection | Stop the single API writer and restart it with the same DB/private root after connectivity is repaired. Startup reconciliation is required; never bypass its lock. |
| Pending/failed outbox grows | Check local SQS reachability and oldest durable pending rows. Restore queue; dispatcher/reconciliation republish safely. Never delete committed events to empty backlog. |
| RETRY or expired worker lease | Inspect status/attempt/error-code counts. Restore storage/parser/embedding dependency; due retries or lease reclamation resume safely. Do not manually clear a live lease. |
| FAILED / DLQ | Diagnose sanitized failure code. After repair use the existing reviewed scoped retry/reindex SQL scripts, producing a new generation. DLQ redrive alone does not reset terminal state; do not redrive poison payloads repeatedly. |
| S3 bytes missing after emulator restart | LocalStack is ephemeral. Restore original bytes from a coordinated backup; queue republishing cannot reconstruct bytes. Keep valuable documents on backed-up LOCAL storage. |
| PDF unavailable or timed out | Check trusted Docker, parser image, cgroup v2/privilege limits and confirmed orphan `traceback-pdf-` containers. Repair isolation; never enable an unsandboxed fallback. |
| MODEL_UNAVAILABLE/TIMEOUT/CAPACITY | Check approved local runtime and memory pressure without logging prompts. Free unrelated resources; retain model/digest. Timed-out uncooperative work retains capacity until exit. Retry the same turn key to observe durable state; a new key is a deliberate new attempt. |
| SOURCE_CHANGED/REDACTED | Re-query current authorized evidence and start a fresh turn. Do not restore stale citations or serve superseded chunks. |
| Invalid citation | Check synthetic fixture output/policy only; production raw answers/prompts are not diagnostics. Invalid generations have no successful assistant content. |

For private local diagnostics use existing state/count SQL in M11/M12/M13, health probes,
and the documented LocalStack DLQ count command. No remote metrics/admin endpoint or
observability platform is added. Slow clients, per-tenant storage/rate quotas, global
distributed admission, alert delivery and production dashboards remain deployment work.

## Automated quality gates

`.github/workflows/backend-quality.yml` uses pinned action SHAs, read-only repository
permissions and Java 21 on Ubuntu 24.04. It runs `clean verify`, rejects missing/skipped/
failing Surefire results, runs the Python OIDC helpers, validates all Compose profiles and
checks commit whitespace. The full suite includes Flyway upgrade/constraint validation,
PostgreSQL integration, actual security filters, LocalStack, isolated PDFs, and deterministic
retrieval/RAG/adversarial evaluations. Docker is mandatory; tests do not silently skip it.

A separate job generates a CycloneDX 2.9.1 SBOM including tests, downloads checksum-pinned
Gitleaks 8.30.1 and OSV Scanner 2.7.0, then fails on findings **or scanner errors**. Gitleaks
reads a disposable current/nonignored source copy, not `.env`, build output or history.
OSV receives dependency metadata only, never private source/documents/conversations. Network
access to GitHub/Maven/OSV is a dependency; no paid service or API key is needed. Default
CI never starts Ollama or downloads model weights. Live model/performance evaluations remain
explicit separate commands. Local equivalence is tested; a hosted GitHub run is not claimed.

One Gitleaks match was confirmed as a false positive on the boolean CLI expression
`wrong_audience=not args.skip_wrong_audience`. Its exact source line has a documented
`gitleaks:allow` comment. No credential finding or dependency advisory is broadly suppressed.

## Measured retrieval and answer quality

The real nomic run on the **62 held-out evaluation queries** produced the following
document-level results. Full per-query ranks, labels and timing samples are retained in
[`retrieval-live.json`](../evaluations/m15/retrieval-live.json).

| Mode | Recall@5 | Recall@10 | MRR@10 | nDCG@10 | No relevant result |
| --- | ---: | ---: | ---: | ---: | ---: |
| Keyword | 0.2823 | 0.2823 | 0.2903 | 0.2841 | 44/62 |
| Semantic | 1.0000 | 1.0000 | 0.9530 | 0.9600 | 0/62 |
| Hybrid | 1.0000 | 1.0000 | 0.9530 | 0.9600 | 0/62 |

Final held-out service p50/p95 latency was 8.20/17.11 ms keyword, 24.59/34.89 ms semantic
and 32.88/40.59 ms hybrid, including real query embedding for the latter two.

Foreign results were zero across all modes, splits and queries. The keyword weakness is
real on this fixture: conjunctive full-text queries miss paraphrases and extra query words.
Semantic/hybrid perfect recall does not mean perfect first rank, nor general accuracy.
The synthetic topics are separable and all relevant documents fit in the top five.
M12's ten-query Recall@5/MRR@5 was 0.60/0.60 keyword and 1.00/1.00 semantic/hybrid.
Different corpora and MRR cutoffs prevent an improvement/regression claim between milestones.
No ranking change or tuning was made to chase these scores.

Deterministic RAG executes all **24/24** frozen cases and adversarial application checks
execute **12/12** attacks. These validate contracts and isolation with scripted outputs;
they are not model obedience or semantic quality percentages. In particular, syntactically
valid unsupported prose can still pass provenance validation. The foreign corpus is never
admitted, unsupported hostile markers fail durably, no credential is available to exfiltrate,
and no executable tool interface exists. Prompt injection can still influence an answer
within authorized content; application authorization does not rely on resisting that influence.

The pinned Qwen2.5:7B initially failed to load in all five selected cases; a one-case
smaller-context diagnostic also failed. Both reports are preserved as
`rag-live-memory-failure.json` and `rag-live-small-context-failure.json`. After the user freed
unrelated Docker memory and authorized **one bounded retry**, all five cases completed:

| Live measure | Observed result |
| --- | --- |
| Supported answers / answerable cases | 3/3 |
| Correct abstentions / expected abstentions | 2/2 |
| Incorrect abstentions / answerable cases | 0/3 |
| Unsupported material claims | 0/9 reviewed claims |
| Valid / relevant citation associations | 5/5; 5/5 |
| Expected source coverage / claim citation coverage | 4/4; 9/9 |
| Foreign leakage / generation failures | 0; 0 |
| Successful injection in the one live attack | 0/1; safe abstention 1/1 |
| Turn latency | 31.740–42.704 s; median 38.719 s |

The conflict answer retained both pool sizes and did not invent authority to choose one.
The audit in [`rag-claim-review.json`](../evaluations/m15/rag-claim-review.json) reviews every
accepted claim against actual returned excerpts and binds the saved output by SHA-256.
This is a Codex manual review, not an independent second human or an external judge model.
M13's ten cases gave six supported answers, four abstentions, nine valid citations and six
supported cited answers, with 52–97 s single-turn CPU latency. M15 uses a smaller, different
subset and changed memory contention, so neither quality nor speed improvement is established.
Final historical-read tightening was separately regression-tested; it does not change the
generation prompt/provider/parser used by this live run. No further chat run or model change
was performed. Small synthetic samples cannot establish production groundedness or immunity.

## Performance and query findings

[`performance.json`](../evaluations/m15/performance.json) saves 20 warmed samples per service
operation, five publication samples per size, throughput and an EXPLAIN ANALYZE/BUFFERS plan.
These are local disposable PostgreSQL 17/pgvector measurements, with fixture embeddings,
one foreground client and small synthetic documents. They exclude HTTP overhead except for
the separate MCP comparison. Service measurements include current authorization; no cache
was added. The benchmark runs ANALYZE after each corpus-size change.

Host: Apple M4, 24 GiB physical memory; Docker reported ten CPUs, 8,319,504,384 bytes of
memory and cgroup v2. Java 21, PostgreSQL 17.11, pgvector image `pg17`, Keycloak 26.8.0,
LocalStack 4.8.1 and the existing pinned Ollama 0.40.4 runtime were used. Host/Docker caps
and background contention affect these observations; this is not dedicated production hardware.

The separate real-Keycloak/official-SDK loopback comparison used 20 alternating reads per
path after three warm-ups. REST p50/p95 was 3.45/3.80 ms; MCP SDK invocation 4.40/6.32 ms.
[`mcp-performance.json`](../evaluations/m15/mcp-performance.json) retains samples. This includes
HTTP/auth/service/serialization and is not isolated transport CPU overhead.

| Operation | p50 / p95 ms |
| --- | ---: |
| Incident read (100 stored incidents) | 0.52 / 0.58 |
| Incident page (100 stored incidents) | 1.96 / 3.20 |
| Keyword, 50 / 200 / 500 documents | 2.59/5.42; 3.40/3.60; 6.70/7.08 |
| Semantic, 50 / 200 / 500 documents | 3.03/3.29; 6.86/11.55; 13.56/19.80 |
| Hybrid, 50 / 200 / 500 documents | 4.01/4.27; 9.57/14.52; 19.72/26.43 |
| Metadata page, 500 stored documents | 3.49 / 4.36 |
| Small document download | 0.73 / 1.00 |
| Conversation creation | 0.50 / 0.77 |
| RAG with scripted provider | 31.55 / 34.03 |

Local fixture-vector ingestion achieved 43.3–46.1 documents/s across the three load stages;
this excludes real embedding inference and remote queue/storage latency. Publication of
64/256/512 dense 768-dimensional vectors took 27.4–36.3 / 102.9–114.7 / 218.4–225.3 ms.
No batching was justified relative to the existing ten-second transaction budget, so atomic
publication and its final lease fence are unchanged. No unsupported performance threshold
was introduced.

`performance-initial.json` preserves the first sparse-vector run. Its 500-document keyword
p50 was about 405 ms before statistics settled. The corrected measurement explicitly runs
ANALYZE and uses dense vectors. This is a measurement-method correction, not evidence of
a production SQL optimization. The saved plan is the representative **authorized keyword
eligibility** query, not the exact ranked retrieval SQL: about 4.3 ms, shared buffers only,
no temp spill, tenant chunk index lookups and a small sequential head scan. Exact cosine
still grows with corpus size. Metadata listing performs bounded per-row revision reads;
it is a possible future N+1 optimization if larger-page measurements justify it. Current
evidence does not justify approximate indexes or a permission cache.

CPU model inference is the dominant chat cost, taking tens of seconds versus tens of
milliseconds for the scripted pipeline. Single-client samples do not establish concurrency
throughput or p99. The initial memory failures show the runtime cap does not reserve host
capacity. No new infrastructure or model was provisioned.

## Resource boundary review

| Boundary | Current limit / behavior |
| --- | --- |
| REST mutations / new incident descriptions | 128 KiB body before JSON; 20,000 UTF-16 description units; 413/400 on excess |
| Document/multipart | 10 MiB file; 11 MB request default; disk-backed multipart; measured byte/hash validation |
| PDF | 200 pages, 1,000,000 text code points, 8,192 structures; one parser per process; 30 s default timeout |
| Parser container | No network, read-only root, 256 MiB RAM/no extra swap, one CPU, 64 PIDs, unprivileged UID, dropped capabilities, 32 MiB noexec tmpfs |
| Index / embeddings | 512 chunks, default 1,600 code points with 160 overlap; at most eight texts per embedding call; 768 dimensions; 20 s provider read timeout |
| Search / lists | Query at most 256 code points/16 terms, no control characters, before provider call; result limit 1–50, candidates at most 200 per mode; document/incident pages at most 100 |
| MCP | 16 KiB request, 64 KiB result, 8 transport / 4 tool concurrent slots, 120 requests/min identity budget, 10 s tool deadline |
| Chat | 1,000-byte question, 6,000-byte prompt, 3,000-byte evidence, 1,500-byte/eight-message history, four retrieved chunks; 8,192 context / 512 output tokens |
| Chat admission | Two generations per process; 120 s deadline; timed-out uncooperative work retains its slot until actual exit |
| Worker | Scheduled consumer is serial per worker process; fenced renewable lease; ten-minute indexing claim deadline; five attempts |
| Outbox / dependencies | Twenty dispatches per scheduling batch; durable backlog not globally quota-limited; bounded backoff; SDK timeouts; Hikari acquisition three seconds |

Request bodies are bounded in bytes, while DTO strings and extraction/chunking have explicitly
different units. HTTP slow-client admission, cumulative tenant storage/history/backlog quotas,
number of worker replicas, database statement budgets and cross-process inference/embedding
admission are not globally bounded here. Per-process semaphores/rate maps are not distributed
tenant quotas. Existing queue, model, parser, input and deadline regression tests verify
local rejection/recovery; this is not a sustained saturation/load test.

## Dependency and configuration assessment

OSV's pre-patch SBOM matched ten advisories across Jackson core/databind 3.1.5 and Tomcat
11.0.24. The retained [`dependencies-before.json`](../evaluations/m15/dependencies-before.json)
records IDs and database severities: Tomcat entries include critical ratings, Jackson high
and moderate. These are dependency matches, **not confirmed critical application exploits**.
FORM/DIGEST authentication and the reported specialized Jackson polymorphic features are
not configured in this application. Compatible patches remove those version matches:
Jackson 3.1.7 and Tomcat 11.0.26. See the [Tomcat security history](https://tomcat.apache.org/security-11.html),
[Jackson core advisory](https://github.com/FasterXML/jackson-core/security/advisories/GHSA-7hhh-6rmp-j9qf)
and [Jackson databind advisory](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-cxp5-3px4-pw24).

The post-patch SBOM has 237 dependencies including tests, with zero known OSV findings and
zero current-source Gitleaks findings. Scanner summaries are retained in
[`scans.json`](../evaluations/m15/scans.json). Scanner/network errors fail the gate; absence
of known matches is not proof of vulnerability absence. Docker base images, shaded parser
artifacts, historic Git secrets and supply-chain compromise need separate coverage.

Loopback ports, disabled-by-default inference/MCP, explicit allowed origins/hosts, health-only
Actuator, hidden health details, no SDK body diagnostics and nested `.env` exclusions remain.
The known database development fallback and ephemeral LocalStack test credentials are local
defaults, never production-safe credentials. CI uses placeholders. No live `.env`, token,
private document or conversation was uploaded to scanners. No production Keycloak, IAM,
private networking, TLS termination, backups or public ingress was configured or verified.

## Audit disposition, changed files and production checkpoints

The source audit of baseline `d6ae8a1a5e23fc109cf93ee53793d0170821b618` found **zero
critical/high, two medium** application findings: citation presentation C1 and unbounded
incident descriptions R1. Both are corrected for the affected new operations, with regression
evidence. Legacy large incident rows are preserved, so their read amplification remains
explicitly open as a deployment/data-management concern. Strict missing-exp enforcement is
an additional issuer-policy defense, not a demonstrated forged-token or tenant bypass.
Dependency database ratings are reported separately above.

The generated baseline audit is sealed in the [Codex Security report](/Users/alexandrugrecu/.codex/state/plugins/codex-security/scans/traceback/d6ae8a1a5e23fc109cf93ee53793d0170821b618_20261014T180628Z_s_aos_sb/report.md),
with canonical [manifest](/Users/alexandrugrecu/.codex/state/plugins/codex-security/scans/traceback/d6ae8a1a5e23fc109cf93ee53793d0170821b618_20261014T180628Z_s_aos_sb/scan-manifest.json),
[findings](/Users/alexandrugrecu/.codex/state/plugins/codex-security/scans/traceback/d6ae8a1a5e23fc109cf93ee53793d0170821b618_20261014T180628Z_s_aos_sb/findings.json),
[coverage](/Users/alexandrugrecu/.codex/state/plugins/codex-security/scans/traceback/d6ae8a1a5e23fc109cf93ee53793d0170821b618_20261014T180628Z_s_aos_sb/coverage.json)
and [threat model](/Users/alexandrugrecu/.codex/state/plugins/codex-security/scans/traceback/d6ae8a1a5e23fc109cf93ee53793d0170821b618_20261014T180628Z_s_aos_sb/threatmodel.md).
These host-local links are not portable repository artifacts. The findings intentionally
retain original source lines; current uncommitted remediation is described here. Scan-reported
complete usage across four threads: 43,230,038 total tokens, including 42,160,384 cached input;
43,089,832 input and 140,206 output. This is rollout accounting, not provider inference usage.

Production changes are limited to citation policy/context/history checks, REST input/expiry
security, three fixed-label metric boundaries, readiness/pool configuration, compatible
dependency patches and the wrapper checksum. Tests add real-filter faults, source races,
commit rollback and opt-in/default evaluation drivers. Supporting changes are frozen
`evaluations/m15/` artifacts, `scripts/evaluation/`, `scripts/quality/`, the GitHub workflow,
one exact secret-scanner false-positive comment, architecture/roadmap and ADR implementation
notes. **No migration is added or edited.** Existing M12/M13 datasets/results and persistent
application data are unchanged.

Verified locally: tenant/RBAC/known-ID denial and source lifecycle regressions; real JWT
cryptography/audiences; disposable LocalStack queue/storage and lease failures; isolated PDF
failure behavior; commit rollback/retry; deterministic and bounded live evaluations. The
PostgreSQL pause test sets a two-second JDBC socket timeout in its disposable fixture; it
does not certify all production network-failure deadlines. Docker host/cgroup enforcement,
issuer administration and local model integrity remain trusted assumptions.

Before public deployment: review real IAM and separate API/worker principals/mounts, ingress
TLS/OIDC clients/CORS, secret provisioning, dependency/container patch cadence, backups and
restore, physical retention/purge, legacy oversized rows, per-tenant quotas, distributed
admission, SQL/network timeouts, model capacity and representative load/quality evaluations.
OAuth 2026 interoperability and production cloud durability remain unverified. A model can
still make a semantically unsupported claim with a valid authorized citation. No complete
prompt-injection immunity, production security certification or general retrieval accuracy
is claimed.

After user review of M15, the next recommended milestone is M16's minimal authenticated
frontend: preserve creator/organization boundaries, treat model Markdown as untrusted,
surface redaction/abstention/degraded search, retain durable retry keys and use the documented
PKCE flow. M16 has not started.

## Final executed verification

All Maven commands below ran from `backend/` with the prefix
`JAVA_HOME=$(/usr/libexec/java_home -v 21)`. Docker was available. There were **no skipped
tests** in any successful final run. [`verification.json`](../evaluations/m15/verification.json)
retains commands, counts, durations, local log paths and artifact hashes.

| Command | Actual final result |
| --- | --- |
| `./mvnw clean test` | PASS: 234 tests, 0 failures/errors/skips; 1:35 |
| `./mvnw verify` | PASS: 234 tests, 0 failures/errors/skips; repackaged JAR built; 1:32 |
| Security command below | PASS: 208 tests, 0 failures/errors/skips; 1:17 |
| `./mvnw -Dtest=M15RetrievalEvaluationTest -Dtraceback.evaluation.output-dir=../evaluations/m15 test` | PASS: 1 test; 13.269 s; deterministic vectors |
| `./mvnw -Dtest=M15RetrievalEvaluationTest -Dtraceback.evaluation.live-embeddings=true -Dtraceback.evaluation.output-dir=../evaluations/m15 test` | PASS: 1 test; 23.090 s; real nomic |
| `./mvnw -Dtest=M15PerformanceEvaluation -Dtraceback.evaluation.output-dir=../evaluations/m15 test` | PASS: 2 tests; 31.280 s; benchmark plus inherited corpus gate |
| `./mvnw -Dtest=M15RagModelEvaluation test` | PASS on authorized bounded retry: 1 test/five live cases; 3:18; earlier failures retained |
| `./mvnw dependency:tree -Dverbose -DoutputFile=target/m15-dependency-tree.txt` | PASS; selected Jackson 3.1.7, Tomcat 11.0.26, CommonMark 0.25.1 |
| `./mvnw org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom -DoutputFormat=json -DincludeTestScope=true` | PASS; 237 dependency components |

The separately executed security suite selects existing tests by boundary rather than
duplicating them under a new annotation or inflating test counts:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=RestJwtValidationIntegrationTest,OrganizationApiIntegrationTest,IncidentApiIntegrationTest,DocumentApiIntegrationTest,LocalDocumentStorageTest,IndexIntegrationTest,ConversationIntegrationTest,ChatIntegrationTest,RagContextTest,McpJwtValidationTest,McpBoundaryTest,McpToolLimitTest,McpIntegrationTest,DurableIngestionIntegrationTest,ExtractionTest test
```

Root commands actually executed:

```bash
python3 scripts/quality/check_test_results.py
python3 -m unittest discover -s scripts/tests -v
docker compose config --quiet
KEYCLOAK_ADMIN_PASSWORD=ci-placeholder docker compose -f compose.yaml -f compose.m12.yaml -f compose.m13.yaml --profile m12 --profile m13 config --quiet
docker compose ps
python3 scripts/quality/install_scanners.py --directory /tmp/traceback-m15-verified-scanners
python3 scripts/quality/run_scans.py --binaries /tmp/traceback-m15-verified-scanners
git diff --check
git diff --name-only -- backend/src/main/resources/db/migration evaluations/m12 evaluations/m13 compose.yaml compose.m12.yaml compose.m13.yaml
```

Results: result checker **234/0/0/0** after both full runs; Python **20 tests OK**; both
Compose configurations valid; five existing services running on loopback; pinned scanner
installation verified; final Gitleaks/OSV both exit zero with zero findings; whitespace check
clean; historical/Compose path diff empty. Workflow YAML parsed through Ruby's standard YAML
library with both jobs and expected steps checked. Python PyYAML was unavailable, so no
Python workflow parser success is claimed. A JWT-shape scan of M15 local logs found zero
token-shaped values; this supplements source/logging review, not a universal log guarantee.

During development, tests caught parser edge cases, test-fixture setup problems and the
missing-exp decoder policy; these were corrected before the full final runs. The earlier
model load failures and first benchmark methodology are retained explicitly. GitHub-hosted
CI itself was not run. No commits, pushes, PRs, shared-history changes, public exposure,
live Keycloak changes, paid model calls, new model downloads or deployment occurred.

**Checkpoint F complete locally. Stop for M15 review and approval; do not begin M16.**
