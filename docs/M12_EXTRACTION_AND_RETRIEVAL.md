# M12 — Document extraction, indexing and hybrid retrieval

**Status:** Implemented locally; milestone review pending. Architecture and local downloads were approved by the user on 2026-10-14. Changes are uncommitted on `codex/m12-extraction-retrieval`. No M13, MCP, OCR, frontend or deployment work is included.

## Architecture and changed files

The new `extraction`, `indexing` and `search` feature packages extend the existing Java 21/Spring Boot 4.1.1 modular monolith. Spring AI 2.0.1's Ollama embedding module is the new application dependency. Maven Assembly 3.7.1 produces a minimal PDFBox 3.0.8 parser JAR; Exec Maven Plugin 3.5.0 builds its image before tests. The parser artifact includes PDFBox dependencies and standalone extraction classes, without Spring, storage adapters, application configuration or credentials.

V7 is append-only. V1–V6 remain unchanged. The existing M11 preparation completion transaction creates indexing work only when indexing is explicitly enabled. The existing dispatcher, SQS consumer, independent worker runtime and recovery scheduler handle both preparation and indexing. No additional job broker or orchestration framework is introduced.

Changed existing paths include `knowledge/DocumentUploadValidator`, ingestion repository/dispatcher/consumer/runtime, Maven/configuration and disposable test fixtures. New artifacts include V7, the parser Dockerfile/assembly descriptor, `compose.m12.yaml`, administrative scripts under `scripts/indexing/`, tests, evaluation assets under `evaluations/m12/`, and accepted ADR-0007. Authentication configuration is unchanged.

## Extraction and isolation

PDF uploads now validate meaningful extractable text in the isolated parser before reservation. The asynchronous indexing worker independently reads and verifies the immutable revision's bytes before extraction. Original bytes, metadata and revision history remain unchanged. TXT/Markdown use strict UTF-8; headings, paragraphs, Unicode and inert fenced code are retained. PDFBox supplies text/paragraphs and one-based PDF page references; PDF headings are not guessed. No OCR, rendering, URL following or content execution occurs.

| Limit | Default / implementation |
| --- | --- |
| Extraction file bytes | `EXTRACTION_MAX_FILE_BYTES=10485760`; configurable 1..10 MiB; worker also respects the storage/document cap |
| Pages | `EXTRACTION_MAX_PAGES=200`, configurable 1..200 |
| Extracted text | `EXTRACTION_MAX_TEXT=1000000` Unicode code points; configurable downward |
| Source blocks / metadata | At most 8,192; heading at most 1,600 code points; conservative 9 MB serialization budget checked before allocation |
| Parser memory | Docker 256 MiB, zero additional swap; JVM heap 160 MiB/direct memory 16 MiB |
| Parser CPU / processes | One CPU, PID limit 64; one parser per application process |
| Wall deadline | `EXTRACTION_TIMEOUT_SECONDS=30`, configurable 1..60; bounded parent cleanup, plus independent 70-second container timeout |
| Temporary disk | Private 32 MiB noexec/nosuid tmpfs; read-only root, no host mounts |
| Network / privileges | Network none, UID/GID 65534, all capabilities dropped, no-new-privileges |

Only measured bytes enter bounded stdin. A bounded binary block protocol exits through stdout; parser stderr is discarded. Filenames/text never enter a command. The parent invokes fixed Docker arguments and removes only its generated opaque container name. The parser verifies Linux cgroup v2 memory, swap, CPU and PID limits and process privilege flags before parsing. Unsupported or unenforced isolation fails closed; there is no native PDF fallback.

Outcomes include NO_TEXT, INVALID_PDF, ENCRYPTED_PDF, FILE_LIMIT, PAGE_LIMIT, TEXT_LIMIT, STRUCTURE_LIMIT, EXTRACTION_TIMEOUT and EXTRACTION_RESOURCE_LIMIT. Scanned/empty PDFs are rejected explicitly. PDF upload validation returns 400 for unsupported/resource-exceeding content, 413 for file limits, and sanitized 503 for unavailable/busy isolation. Previously stored PDFs can fail extraction with a durable outcome. Larger TXT/Markdown files allowed by an increased M10 upload cap can remain downloadable but fail the stricter extraction cap.

Docker daemon access is trusted host orchestration privilege. The parser receives no daemon socket, credentials, repository mounts or database connections. Containers share the daemon's Linux kernel: this is not a VM boundary against kernel/runtime exploits. Parent cleanup can fail during a daemon outage; the internal timeout bounds normal orphan execution. After stopping relevant workers, inspect `docker ps --filter name=traceback-pdf-` and remove only confirmed orphan names. VM/daemon failure is not a hardware-failure certification.

## Chunking and source references

`structure-v1` prefers sections and complete paragraphs. It combines small blocks, splits oversized blocks into at most 1,600 Unicode code points, and uses configurable overlap (default 160). It avoids overlap across different headings; paragraph carry is used when it fits. Oversized blocks use the configured sliding overlap. At most 512 chunks are accepted. Limits are `INDEX_CHUNK_SIZE`, `INDEX_CHUNK_OVERLAP` and `INDEX_MAX_CHUNKS`.

Stable logical chunk IDs depend on immutable revision ID, canonical configuration hash, ordinal and text checksum. Physical keys include generation so identical reindexing can preserve logical identities without mixing rows. Metadata retains section, PDF page range and normalized extraction offsets. Page ranges/offsets describe the source span; layout-perfect PDF section semantics are not claimed. There is no exact tokenizer: conservative character bounds include task-prefix allowance, and provider truncation is disabled so oversized token sequences fail explicitly.

## Embeddings and compatibility

The real provider is local Ollama, through `EmbeddingProvider` and Spring AI's Ollama embedding module. It uses `nomic-embed-text:v1.5`, 768 dimensions, and the required `search_document:` / `search_query:` prefixes. The verified model digest is:

```text
sha256:0a109f422b47e3a30ba2b10eca18548e944e8a23073ee3f3e947efcf3c45e59f
```

The adapter validates the local manifest digest before and after embedding, result ordering/count, finite components, dimensions and nonzero norm. Only local HTTP endpoints are accepted; redirects and models carrying remote_host/remote_model metadata are rejected before sending text. The provided runtime disables cloud features with OLLAMA_NO_CLOUD=1; external paid provider activation is not supported. Operators must not mutate the pinned local model while processing. Digest/model/dimension/chunker changes require a new immutable configuration and reindex. Incompatible equal-dimensional vectors are excluded by configuration predicates. Existing valid generations remain keyword-searchable during model replacement; semantic retrieval only considers the configured compatible generation.

`EMBEDDING_ENDPOINT` defaults to `http://127.0.0.1:11435`. Set `EMBEDDING_MODEL`, `EMBEDDING_MODEL_DIGEST`, `EMBEDDING_DIMENSIONS` and `EMBEDDING_TIMEOUT_MS` explicitly as needed. Automatic pulling is disabled. Batches are bounded to eight, HTTP connect timeout is two seconds and read timeout defaults to 20 seconds. Durable job retries provide retry bounds; there are no unbounded nested provider retries. Query failure is surfaced or explicitly degraded, rather than retried indefinitely.

Local inference has no per-call API fee. The model download is about 274 MB. The pinned Ollama 0.40.4 runtime image reports approximately 6.6 GiB uncompressed, including runtime libraries; account for that disk footprint. Opt-in Compose caps inference at two CPUs and 2 GiB memory/no additional swap, one loaded model and one concurrent request. No private documents were used for verification. No text, raw queries, vectors, request bodies or provider errors enter application diagnostics.

## V7 schema and publication

| Artifact | Responsibility |
| --- | --- |
| `index_configurations` | Canonical hash, immutable model/digest/dimensions, extractor/chunker versions and output bounds |
| `index_generations` | Durable job, phase/state, attempts, next retry, token lease, start/update/publication times, sanitized error, chunk count and duration |
| `extraction_artifacts` | Bounded structured blocks and canonical checksum, owned by one generation/revision |
| `document_chunks` | Stable logical ID/ordinal, bounded content, checksum, source metadata and generated `simple` tsvector |
| `chunk_embeddings` | Composite ownership/configuration FK and dimension/nonzero constrained pgvector vector |
| `document_index_heads` | One active generation per revision; publication trigger requires complete equal chunk/vector counts |
| `ingestion_outbox` extension | Typed VERIFY/INDEX events, composite indexing-generation FK and separate event uniqueness |

Composite tenant/document/revision/generation/configuration foreign keys prevent foreign association. State/lease checks, one unfinished generation per revision, bounded text/counts, due/tenant indexes and GIN full-text indexes support correctness and operations. Exact cosine retrieval is used; no HNSW/IVFFlat index was added for the tiny corpus. Dimension changes fit a new configuration in the dimension-checked `vector` column. Schema changes never rewrite original revisions. The PostgreSQL server must provide pgvector and the migration role must be allowed to create the extension; the persistent local database was not migrated during this work.

Do not run old M11 workers alongside typed M12 event producers. Build/cut over API and worker together locally. Old v1 verification messages remain supported, and the V6-to-V7 migration preserves existing jobs/events while assigning VERIFY to historical events.

## Durable lifecycle and failure policy

AVAILABLE describes stored source bytes. VERIFIED still describes integrity preparation. Neither means extracted or searchable.

1. Enabled M11 verification completion atomically commits VERIFIED plus PENDING_EXTRACTION generation and INDEX outbox event. An event insert failure rolls both back.
2. The existing dispatcher sends v1 verification or v2 indexing references. Every v2 ID must match its persisted typed event before content access. Queue selectors/roles are never authority.
3. The worker locks document before generation, requires active/current AVAILABLE source plus VERIFIED preparation, and claims with a fresh 90-second token lease. Different documents can process concurrently.
4. EXTRACTING reads bounded private bytes and checks size/SHA-256. Successful extraction commits a private checksummed artifact and EXTRACTED checkpoint. Retry validates and reuses that artifact.
5. INDEXING chunks/embeds outside database transactions. A 30-second heartbeat renews database ownership and SQS visibility. Renewal and completion reject lost/expired tokens, changed revision/tombstone and attempts beyond a ten-minute per-claim deadline.
6. All chunks/vectors, INDEXED state and head switch commit in one transaction, with validity/count/lease rechecks and a database completeness trigger. No partial keyword or semantic index is exposed.
7. Acknowledgement follows durable publication/handled duplicates. Transient storage/parser availability/embedding failures become RETRY; permanent content/model/vector failures become FAILED. Five attempts bound retries/crashes. Backoff is 2..32 seconds. Failed messages remain for M11's queue DLQ policy; successful duplicates are no-ops.
8. Existing periodic recovery also rearms delivered indexing events older than 180 seconds for due unfinished expired work. It never resets a live lease or indexed/failed/cancelled generation.

A crash or commit/acknowledgement failure leaves durable state for redelivery. Workers losing ownership cannot publish. A failed reindex leaves the valid old head intact; repeated requests while replacement is pending share one unfinished generation. A later explicit reindex after completion intentionally creates a new generation. PDF/storage operations already in flight cannot be retracted, but late output cannot become visible.

Revision publication cancels unfinished older indexing; search independently joins the current pointer. New current content is absent until fully indexed, rather than returning superseded content. Tombstones immediately remove all revisions from search/ranking/counts and cancel unfinished work in the same document transaction. Historical originals, artifacts and vectors remain privately retained. No purge, data-erasure or restore policy is implemented.

## Search API and authorization

```text
GET /api/v1/organizations/{org}/search?q=HTTP%20503&limit=10&mode=hybrid
GET /api/v1/organizations/{org}/documents/{doc}/revisions/{rev}/indexing
```

```bash
curl -G -H "Authorization: Bearer $TOKEN" --data-urlencode "q=HTTP 503" \
  --data-urlencode "mode=hybrid" --data-urlencode "limit=10" \
  "$BASE/api/v1/organizations/$ORG/search"
```

Search modes are keyword, semantic and hybrid (default). Require a nonblank query with a letter/digit, at most 256 Unicode code points/16 lexical terms, and limit 1..50. Candidate pools are bounded to four times limit, at most 200. All queries bind parameters; no advanced client-controlled tsquery syntax or SQL fragments are accepted.

The response contains requestedMode, effectiveMode, degradationCode, returnedCount and results. Each result has a `source` containing documentId, revisionId, chunkId, generationId, title, excerpt (at most 600 code points), section/page range, ordinal, configuration/model/dimensions and its candidate score. keywordRank, semanticRank and the final score identify ranking behavior. Search responses use Cache-Control: no-store. Source text remains untrusted display data. Storage keys/paths, receipts, credentials and private provider diagnostics are absent.

The indexing status response shows the newest generation's status/phase, attempts, sanitized error, duration, publication time, configuration/chunk count and whether that generation is published. A failed newest replacement can coexist with a previously published valid head. Authorized historical status reads reuse document/revision policy; deleted/foreign IDs return generic 404, nonmembers 403 and unauthenticated calls 401.

Keyword retrieval uses PostgreSQL `simple`, preserving engineering identifiers without stemming. `plainto_tsquery` ANDs lexical terms. Supplemental case-insensitive literal substring matching uses `strpos`, so punctuation and SQL/LIKE metacharacters are literal. Full-text tokenization can split punctuation-heavy identifiers; substring matching does not provide arbitrary fuzzy/prefix matching. This strict lexical strategy explains weak paraphrase recall in the evaluation.

Semantic retrieval uses exact cosine distance with configuration/model compatibility inside SQL. Hybrid combines independent candidate ranks using RRF with k=60, deduplicates by generation/chunk and breaks ties by stable IDs. It never adds incompatible raw scores. Hybrid provider failure returns explicitly degraded keyword results; semantic-only returns sanitized 503. Unpublished chunks are absent in every mode.

Services require the existing trusted human claim and current database membership before embedding a query, then reauthorize after provider I/O. Candidate SQL independently includes tenant, issuer/subject membership, active/current source, INDEXED generation and published-head predicates **before ORDER BY/LIMIT**. ADMIN/ENGINEER/VIEWER can retrieve. JWT/client roles/organization fields do not grant membership. A removed membership takes effect with the existing JWT. Both candidate lists share one short retrieval snapshot; already authorized in-flight responses have a snapshot limitation like existing downloads.

## Local setup and operations

Maven requires Java 21 and Docker. `clean test` / `verify` build the parser image automatically. API/worker hosts need trusted Docker CLI access and the same build's parser image; workers still use read-only legacy storage and skip M10 upload recovery. One API writer remains required.

```bash
# backend/: parser artifact/image and application build, using disposable tests
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw verify

# root: opt-in model service, after approving download/disk/resource requirements
# Do not stop or reset the existing PostgreSQL/Keycloak/LocalStack volumes.
docker volume create traceback_m12_models
docker compose -f compose.yaml -f compose.m12.yaml --profile m12 up -d --no-deps ollama
docker compose -f compose.yaml -f compose.m12.yaml --profile m12 exec ollama ollama pull nomic-embed-text:v1.5

# Export for BOTH host-run API and worker; Spring does not read .env automatically.
export INDEXING_ENABLED=true
export EMBEDDING_MODEL_DIGEST=sha256:0a109f422b47e3a30ba2b10eca18548e944e8a23073ee3f3e947efcf3c45e59f
export EMBEDDING_ENDPOINT=http://127.0.0.1:11435
# Keep M11's existing local AWS/storage environment and initialized private directory.
# Start API and the same-build --spring.profiles.active=worker as documented in M11.
```

Indexing defaults to disabled until explicitly configured, preserving startup without an installed model. PDF isolation is required regardless of embedding enablement. Existing VERIFIED revisions are not automatically backfilled. For each deliberately selected active current revision: use `scripts/indexing/prepare-legacy.sql` if preparation is absent, wait for VERIFIED, then use reindex with a configuration already created by the enabled runtime. Existing FAILED preparation uses M11's explicit retry script. Never select historical/deleted revisions or reset source data.

```bash
# Trusted local psql; credentials supplied by standard secure local configuration.
psql -v ON_ERROR_STOP=1 -v org="$ORG" -v doc="$DOC" -v rev="$REV" -f scripts/indexing/prepare-legacy.sql
psql -v ON_ERROR_STOP=1 -v org="$ORG" -v doc="$DOC" -v rev="$REV" -v config="$CONFIG" -f scripts/indexing/reindex.sql
```

Reindex creates a new derived generation/event after repairing the underlying failure, preserving the previous head. Scripts lock document first, reject noncurrent/deleted/mismatched selectors and avoid concurrent duplicate generations. They require privileged local database access and are not public API endpoints. A terminal job's old delivery remains terminal; the replacement generation has its own event.

Safe SQL inspection avoids document contents and storage keys:

```sql
SELECT status, phase, count(*) FROM index_generations GROUP BY status, phase;
SELECT id, attempts, error_code, updated_at FROM index_generations WHERE status='FAILED';
SELECT count(*) FROM index_generations WHERE lease_until<clock_timestamp();
SELECT count(*) FROM ingestion_outbox WHERE event_type='INDEX' AND delivered_at IS NULL;
```

Micrometer provides state counts, visible current indexed-revision count, expired leases, extraction outcomes, embedding failures, completion/retry counters and processing duration. M11 outbox/queue operational counts and DLQ inspection remain applicable. Labels contain only fixed states/outcomes; no IDs, queries or source text. Health is the only exposed Actuator surface. Process-local counters/timers reset; persisted jobs remain authoritative. No unauthenticated administration was added.

## Retrieval evaluation

`evaluations/m12/dataset.json` was frozen before measurement: five wholly synthetic English documents (database, API, authentication, rollback and JVM postmortem), ten labeled source sections/chunks, and ten queries split between five exact technical terms and five paraphrases. A duplicate corpus in a second tenant tests contamination. The opt-in `RetrievalQualityEvaluation` uploads, verifies, extracts, chunks, embeds with the real local model, publishes, then uses the same scoped retrieval service. It uses disposable pgvector PostgreSQL and synthetic authenticated principals; no paid API, real AWS or production content.

```bash
# backend/, with the explicitly pulled local model ready on port 11435:
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=RetrievalQualityEvaluation test
```

The class name deliberately excludes it from the deterministic default suite; it is not a skipped test. `results.json` records dataset hash, model digest/configuration, dimensions, pgvector version, per-query ranked source labels and actual metrics. Recall@K measures retrieved relevant labels; MRR@5 measures reciprocal rank of the first relevant source within five results.

| Mode | Recall@3 | Recall@5 | MRR@5 |
| --- | ---: | ---: | ---: |
| keyword | 0.60 | 0.60 | 0.60 |
| semantic | 1.00 | 1.00 | 1.00 |
| hybrid | 1.00 | 1.00 | 1.00 |

Foreign results: **0**. Measured with 768-dimensional nomic v1.5 and pgvector 0.8.7. This tiny, explicit-label corpus is useful smoke evidence; it is not production quality validation or evidence that hybrid always improves on semantic search. Exact cosine is sufficient here; approximate index/reranker and broader multilingual/ambiguous-query evaluation remain future decisions.

## Verification and limitations

Baseline `clean test` passed 106 tests. Checkpoints passed extraction/upload regressions (35), chunking/migrations (5), indexing/retrieval (initial 15), expanded extraction/indexing failure cases (28), and shared real SQS/M11 regression tests (26). Initial compile/test failures during development were corrected; final evidence is recorded below after the complete runs.

Final verification (Java 21.0.12.1, 2026-10-14; Maven commands in `backend/`):

| Exact command | Result |
| --- | --- |
| `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw clean test` | PASS: 152 tests, zero failures/errors/skips; 46.584 seconds |
| `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw verify` | PASS: 152 tests, zero failures/errors/skips; executable JAR packaged; 45.337 seconds |
| `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=RetrievalQualityEvaluation test` | PASS: final real-model evaluation on the completed implementation, one test, zero failures/errors/skips; 11.880 seconds |
| `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=OllamaProviderTest,RetrievalQualityEvaluation test` | PASS: six tests (five provider/deadline/privacy tests and one real-model evaluation), zero failures/errors/skips; 14.174 seconds |
| `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw dependency:tree '-Dincludes=org.springframework:*,org.springframework.ai:*'` | PASS: Spring AI 2.0.1 and Boot-managed Spring 7.0.9 selected |
| `docker compose config --quiet` (root) | PASS |
| `docker compose -f compose.yaml -f compose.m12.yaml --profile m12 config --quiet` (root) | PASS |
| `docker compose -f compose.yaml -f compose.m12.yaml --profile m12 ps` (root) | Existing PostgreSQL/LocalStack healthy, Keycloak running, opt-in Ollama running; ports loopback |
| `git diff --check` (root) | PASS; all 36 new files also checked for trailing whitespace |
| `git diff --exit-code -- backend/src/main/resources/db/migration/V{1..6}*.sql backend/src/main/java/dev/traceback/security/SecurityConfig.java` (root) | PASS: historical migrations and JWT policy unchanged |

The default suite contains the original 106 tests plus 46 M12 cases: eight extraction,
three chunking, one V7 upgrade, 26 indexing/retrieval/failure/security, five provider,
two RRF and one additional real SQS pipeline test. Real-model evaluation is invoked
separately, not silently skipped. No paid API/AWS/production data is used.

Intermediate development failures included constructor/package-name compile issues,
Mockito restubbing a throwing mock, and an incorrect PDF text-limit outcome. All were
corrected before final passing runs. A shell wildcard needed quoting for dependency
inspection; the corrected exact command above passed. Controlled failure injection can
emit the established generic recovery warnings. Existing optional Maven metadata/Mockito
agent warnings did not fail tests. A temporary staging cleanup command was rejected by
automatic execution review; model preservation instead used a public-model-only Docker
archive stream into a new volume, with no local file removal.

Review covers tenant filters inside both retrieval queries, membership revocation after provider I/O, v1/v2 reference forgery, complete publication/count checks, stale lease/deadline fencing, revision/tombstone races, unchanged JWT policy and old migrations. No persistent Compose database was migrated; no original files, secrets, JWTs or production documents were read. No commits, pushes, PRs, resets or deployments occurred. The initial evaluation runtime was disposable and stopped. Only public downloaded model files were copied into the new traceback_m12_models volume; no keys or credentials were copied. The opt-in Compose Ollama service is running on loopback port 11435 with cloud features disabled; existing Compose services/data were preserved.

Remaining constraints: one API writer, trusted local Docker daemon/cgroup v2, no kernel exploit guarantee, retained historical artifacts with no purge, conservative character chunking, no OCR/PDF layout reconstruction, no approximate-vector performance evidence, and no production IAM/deployment verification. Real Keycloak cryptography was not repeated because authentication configuration is unchanged; M12 security tests use synthetic principals. Only local configured inference exists. After reviewing M12, M13 may consider authorized context assembly/citation validation and grounded responses; it has not started.

## Primary sources

[Spring AI compatibility](https://docs.spring.io/spring-ai/reference/getting-started.html) confirms 2.0.x support for Boot 4.0.x/4.1.x. [Ollama embeddings](https://docs.spring.io/spring-ai/reference/api/embeddings/ollama-embeddings.html) and the [embed API](https://docs.ollama.com/api/embed) document batching/truncation. The [Ollama model listing](https://ollama.com/library/nomic-embed-text:v1.5) and [Nomic model card](https://huggingface.co/nomic-ai/nomic-embed-text-v1.5) describe size, dimensions and prefixes. [Ollama local-only controls](https://docs.ollama.com/faq), [Docker controls](https://docs.docker.com/engine/containers/resource_constraints/) and [pgvector](https://github.com/pgvector/pgvector) describe the isolation/retrieval primitives. Their published benchmarks are not Traceback measurements.
