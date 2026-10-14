# M10 — Knowledge base and document management

Implemented locally on 2026-10-13; subsequently approved for local development. M9A/M9B and the previously verified Keycloak configuration are preserved. This milestone contains document management only: no extraction, OCR, embeddings, RAG, MCP, AWS, queues, or frontend.

This document records the M10 baseline. M11 extends its storage abstraction and adds preparation jobs while preserving these contracts; see [M11_DURABLE_INGESTION.md](M11_DURABLE_INGESTION.md) for current multi-process worker behavior.

## API contract

All routes are relative to `/api/v1/organizations/{organizationId}/documents`. Requests require the existing validated bearer JWT and trusted `traceback_principal_type=human` classification. Identity remains `(issuer, subject)`; roles come from current PostgreSQL memberships.

| Method | Route | Input / result |
| --- | --- | --- |
| POST | collection | Multipart `title` and `file`; 201, item Location and DocumentResponse. |
| GET | collection | `page=0`, `size=20` (1..100), optional `status` (defaults to AVAILABLE). Returns paginated documents and scoped totals. |
| GET | `/{id}` | Document metadata; deleted documents return 404. |
| PATCH | `/{id}` | JSON `{"version":2,"title":"Updated runbook"}`; 200. Title is required, nonblank, at most 200 characters; trimmed on persistence. No-op rename preserves version. |
| GET | `/{id}/content` | Current AVAILABLE revision as a download attachment. |
| POST | `/{id}/revisions` | Multipart `file` plus required expected `version`; 201, revision-content Location and updated DocumentResponse. |
| GET | `/{id}/revisions` | `page=0`, `size=20` (1..100); paginated attempt history, including pending/failed attempts. |
| GET | `/{id}/revisions/{revisionId}/content` | Specific AVAILABLE historical revision; failed/pending/foreign revisions return 404. |
| DELETE | `/{id}?version=n` | Expected nonnegative version; creates a tombstone, returns 204. Repeated authorized deletion returns 204 even with the earlier version. |

Document lists sort by creation time DESC, UUID DESC. Revision lists sort by revision number DESC, which is unique within a document. A repeatable-read transaction makes each list's items and counts consistent. Offset pagination is deterministic within a snapshot; separate requests can shift under new inserts.

DocumentResponse contains `id`, `organizationId`, `title`, `createdBy`, `status`, `version`, `createdAt`, `modifiedAt`, and nullable `currentRevision`. RevisionResponse contains `id`, `organizationId`, `documentId`, `revisionNumber`, `originalFilename`, `mediaType`, `fileSize`, `checksum`, `uploadedBy`, `status`, and `createdAt`. Uploader/creator UUIDs reference durable application identities. Filesystem paths and opaque storage keys are internal and absent from all response DTOs. Deleted collection entries have a null `currentRevision`.

ADMIN and ENGINEER can read/upload/revise/rename/delete. VIEWER can read metadata/history and download. Nonmembers, nonexistent organizations, and machine principals receive 403 before document lookup. An authorized caller selecting a missing, foreign, or deleted document receives a generic 404. Stale versions and pending-upload mutation conflicts return 409. Invalid requests/types/content return 400; oversized requests/files return 413; inaccessible/corrupt storage or publish failure returns a generic 503. Validation errors use Spring ProblemDetail. A low-level database failure during initial reservation rolls the transaction back and returns a generic 503; it does not leave a document or file.

### Example usage

These commands use variables supplied by the caller. Do not paste tokens into source files, logs, or chat. `BASE` can be `http://localhost:8080`; `ORG`, `DOC`, and `REV` are UUIDs. `TOKEN` is the existing human-login token.

```bash
curl -H "Authorization: Bearer $TOKEN" \
  -F 'title=Database recovery runbook' \
  -F 'file=@runbook.md;type=text/markdown' \
  "$BASE/api/v1/organizations/$ORG/documents"

curl -H "Authorization: Bearer $TOKEN" \
  "$BASE/api/v1/organizations/$ORG/documents?page=0&size=20&status=AVAILABLE"

curl -H "Authorization: Bearer $TOKEN" \
  -F 'version=2' -F 'file=@runbook-v2.md;type=text/markdown' \
  "$BASE/api/v1/organizations/$ORG/documents/$DOC/revisions"

curl -H "Authorization: Bearer $TOKEN" --output downloaded-runbook.md \
  "$BASE/api/v1/organizations/$ORG/documents/$DOC/revisions/$REV/content"

curl -X PATCH -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"version":4,"title":"Updated recovery runbook"}' \
  "$BASE/api/v1/organizations/$ORG/documents/$DOC"

curl -X DELETE -H "Authorization: Bearer $TOKEN" \
  "$BASE/api/v1/organizations/$ORG/documents/$DOC?version=5"
```

Always use the actual returned version; the numbers illustrate one upload, one revision, and one rename. No document/revision/storage IDs or uploader identity supplied in multipart fields confer ownership or permissions.

## Schema and immutable history

Append-only `V5__create_documents_and_revisions.sql` creates:

- `documents`: UUID identity and organization FK, title, creator FK, lifecycle status, optimistic version, current-revision pointer, creation/modification timestamps.
- `document_revisions`: UUID identity, organization/document ownership, monotonically increasing revision number, original basename, validated canonical media type, measured byte count, SHA-256, opaque server-generated UUID key, uploader FK, attempt status and creation timestamp.
- Composite foreign keys `(organization_id, document_id)` and `(organization_id, document_id, revision_id)` prevent a revision or current pointer from belonging to another organization/document. Owner/user deletion is restricted.
- Unique document/revision numbers, unique storage keys, one pending revision per document, allowed-status/media/size/checksum constraints, and tenant/date/status/history indexes.
- A revision update trigger permits exactly one UPLOADING → AVAILABLE or UPLOADING → FAILED transition, without changes to any other column. Finalized content metadata cannot be updated through ordinary SQL. No revision update/delete API or unscoped CRUD repository is exposed.

V1–V4 are unchanged. V5 does not update/delete existing incidents, users, organizations, or memberships. Hibernate remains `ddl-auto: validate` for existing JPA entities; new knowledge records use explicit JDBC mapping and Flyway constraints, so the new tables are verified through migration/integration tests rather than Hibernate entity validation.

A revision number describes an immutable content attempt, not optimistic locking. Failed attempts are retained, consume a number, and never replace the current revision. A successful first upload returns content revision 1 and document version 2. Each reservation increments the optimistic version; publishing or recording failure increments it again. A title change or deletion increments it once. Clients must use returned versions, not derive them from revision numbers. Server row locks serialize mutations; pending uploads block rename/delete/another revision, and stale requests are never silently retried.

## Lifecycle and consistency

Document states: UPLOADING → AVAILABLE or FAILED; FAILED can be retried by uploading a revision; AVAILABLE/FAILED can become DELETED. During an upload of a new revision, an existing AVAILABLE document keeps its old current content. Revision attempt states are UPLOADING → AVAILABLE or FAILED. AVAILABLE here means stored and retrievable, not extracted or indexed.

The service deliberately does not hold a database transaction across filesystem writes:

1. Authorize before content parsing; validate bounded bytes and checksum.
2. In a fresh READ_COMMITTED transaction, reauthorize, lock an existing document if revising, compare its expected version, reject any pending attempt, and persist the document/revision reservation. SQL writes independently include ADMIN/ENGINEER membership predicates. Commit the pending record before any object write.
3. Write the private file under the persisted UUID key to a CREATE_NEW `.part` file; fsync the file, atomically rename to `.blob`, then fsync the directory. Existing objects cannot be overwritten through the adapter.
4. In a new transaction, reauthorize and lock the document; atomically transition the revision to AVAILABLE and point the document at it. Only then return success.
5. On failure, a separate maintenance transaction locks the document and transitions only a still-pending revision to FAILED. The old current pointer remains intact; a first-upload document becomes FAILED. Delete its staged/promoted file after the failure is durably recorded. If deletion fails, retain the FAILED record for startup cleanup. If the database is unavailable, retain the private object and pending record for recovery. If a commit succeeded but its acknowledgement was lost, compensation sees AVAILABLE and preserves the committed object.

A crash before reservation commit creates no file. A crash while writing or after promotion leaves a durable UPLOADING record and its known key. Startup marks interrupted attempts FAILED, repairs document status, and removes both `.part` and `.blob` files for FAILED records. Cleanup is idempotent; inability to reconcile fails startup. Ordinary failure cleanup logs only a generic recovery-needed message, never filenames/content/keys or underlying exception paths. No public filesystem/static-file route exists, so residual failed objects are inaccessible through the API.

Startup recovery assumes no other process is uploading. The local adapter holds an exclusive filesystem lock; the runtime also holds a PostgreSQL session advisory lock for the process lifetime. A second process using the same root or database is refused, including one configured with another directory. Requests fail closed if the lock connection is invalid. The lock consumes one pool connection; keep the pool size at least two (default is ten). Multi-instance deployment and lease-based/background recovery are intentionally deferred. Maintenance recovery SQL is internal and unscoped because it must reconcile all interrupted attempts; user-facing SQL remains tenant/identity scoped.

## Storage and validation

`DocumentStorage` has only `put(UUID, byte[])`, bounded `read(UUID, maximumBytes)`, and idempotent `delete(UUID)`. The application resolves keys only from authorized persisted revisions. An S3 adapter can implement this contract for bounded files later; multipart/presigned uploads, queues and distributed recovery require a separately reviewed workflow.

Configuration:

| Setting | Default / meaning |
| --- | --- |
| `DOCUMENT_STORAGE_DIRECTORY` | `${user.home}/.local/share/traceback/documents`; absolute private POSIX directory outside the checkout. |
| `DOCUMENT_MAX_FILE_SIZE_BYTES` | 10485760 (10 MiB); server validator allows a configured range of 1 byte..50 MiB. |
| `DOCUMENT_MAX_REQUEST_SIZE` | `11MB`; servlet multipart envelope cap, which must exceed the intended file cap plus form overhead. Increase it explicitly when increasing the file cap. Lowering the file cap below an existing stored file also prevents its download (503) until the cap is restored. |

Storage rejects relative paths, repository descendants and symlinked path components. It initializes only an empty directory or one bearing the valid `.traceback-storage` marker; it does not repurpose nonempty personal directories. Directory permissions are 0700 and object files are 0600. UUID keys never use filenames or request paths. Reads refuse symlinks and enforce the byte limit; downloads verify exact persisted size and SHA-256, failing closed on missing/corrupt content. The POSIX adapter depends on atomic rename and directory fsync; unsupported filesystems fail uploads. Do not share this directory with untrusted OS users or mount it as public static content. Database and storage must be backed up/restored together and restarted with the same storage directory.

Validation accepts only matching `.pdf`/`application/pdf`, `.md`/`text/markdown`, and `.txt`/`text/plain`, with optional UTF-8 charset. It rejects empty/blank text, malformed UTF-8, binary control characters, PDF bytes presented as text, unsupported extensions, mismatched MIME, control characters or path separators in filenames, and files larger than the measured streaming limit even if declared size is false. A filename is display metadata only. The stricter MIME contract intentionally rejects `application/octet-stream`; clients such as curl should set the supported type explicitly.

PDFBox 3.0.8 is the only added direct dependency, justified by structural PDF validation rather than a header-only check. A strict parser rejects malformed/encrypted PDFs; documents must contain 1..200 pages and nonempty page text-showing operators. Decoded page streams have a combined 20 MiB cap. Text is not extracted, rendered, indexed, or interpreted as instructions. This conservative check rejects image-only PDFs and PDFs with text only inside nested Form XObjects. It is not full PDF/A validation or a guarantee that all fonts render correctly. PDFBox documentation: [Maven dependency](https://pdfbox.apache.org/3.0/getting-started.html), [3.0 migration/API loading](https://pdfbox.apache.org/3.0/migration.html).

Downloads recheck membership, organization/document ownership and revision availability in database queries immediately before bounded file reading. A document row lock prevents a concurrent deletion from completing before content has been materialized. Download permission is evaluated at the scoped-query snapshot; a membership revoked after that point cannot retract bytes already authorized for an in-flight response. Responses use attachment disposition, canonical content type, `nosniff`, and `Cache-Control: no-store`. Clients receive bytes rather than a deferred filesystem resource, so Spring does not open a file after service authorization has ended.

## Deletion and future retrieval

DELETE creates a durable DELETED tombstone. All document/history/content/revision-upload/rename item routes then return 404; only an explicitly filtered authorized collection exposes tombstone metadata. Repeat deletion is idempotent after membership/write authorization. Historical revisions and files remain privately retained; this milestone has no restore or physical purge API. Retention avoids pretending to have a coordinated deletion policy for future ingestion/indexes. Future retrieval must join against active authorized documents, and a future purge/index-invalidation design must consume the tombstone transactionally before erasing objects. Soft deletion does not reclaim disk space or provide a data-erasure guarantee.

## Tests and verification

Tests use PostgreSQL 17 Testcontainers, synthetic validated JWTs with a mocked decoder, and temporary private storage. Existing incident/organization Spring tests also receive temporary storage; no existing local application data or Compose volume is used. The PDF fixture constructs page content without scanning host fonts or writing a home-directory font cache.

Coverage includes supported-format upload/download, actual measured checksums, malformed/empty/oversized inputs, real servlet multipart rejection over loopback HTTP, path traversal and symlinks, known foreign document/revision UUIDs, mismatched document/revision pairs, scoped pagination/counts/status, all membership roles, anonymous/generic/explicit machine denial, current role revocation, independent repository scope, immutable history, stale versions, concurrent revisions and metadata edits, pending mutation denial, role downgrade during upload, reservation/publish rollback, partial storage failures, failed cleanup retry, simulated crashes before/after promotion, compensation preserving AVAILABLE objects, checksum corruption/missing files, tombstone repeat deletion, SQL constraints, and V4→V5 upgrade preserving existing records. Existing incident/organization suites remain regression coverage. Startup interruption tests invoke the same recovery routine used by startup; they simulate persisted crash states rather than power-cycling a machine.

Final verification on Java 21.0.12.1 (commands from `backend/` unless stated):

| Command | Actual result |
| --- | --- |
| `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=DocumentApiIntegrationTest test` | First targeted pass: 23 tests, zero failures/errors/skips. |
| `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=DocumentApiIntegrationTest,LocalDocumentStorageTest,DocumentMigrationIntegrationTest test` | Expanded pass: 27 tests; later 32 tests after the structured multipart-error fix, zero failures/errors/skips. |
| `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw clean test` | Final pass: 79 tests, zero failures/errors/skips. |
| `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw verify` | Passed: 79 tests, zero failures/errors/skips; executable Spring Boot JAR packaged. |
| `docker compose config --quiet` (root) | Passed. |
| `docker compose ps` (root) | PostgreSQL healthy and Keycloak running, both bound to loopback. |
| `git diff --check` (root) | Passed; new M10 files also checked for trailing whitespace. |

The final suite contains the existing 45 organization/incident/migration tests plus 29 document API/service/security/failure/concurrency tests, four filesystem tests and one document migration test. The API suite includes an actual loopback servlet request for multipart limits; the other requests use MockMvc. Testcontainers and synthetic documents isolate storage and database effects.

Earlier development checks were corrected before final verification: the first compile found a logger package name shadowed by a parameter and incorrect PDFBox strict-parser API usage; both were fixed. A targeted 31-test run failed one assertion because real servlet-level 413 rejection had an empty response body; a narrow global multipart exception handler now returns ProblemDetail, and that check passes. Maven emitted existing nonfatal optional repository-metadata 401 warnings and Mockito agent warnings; failure-injection tests intentionally produce generic recovery warnings.

## Self-review and changed files

M10 adds the `knowledge` package (controller/service, explicit tenant-scoped repository and internal persistence records, response/request DTOs, lifecycle enum, bounded upload validator, failure errors, runtime recovery and storage interface/POSIX adapter), `api/UploadLimitExceptionHandler`, V5, document/storage/migration tests, and this document. It changes `backend/pom.xml` for PDFBox and `application.yaml` for size/storage settings. Existing organization/incident API test fixtures now override the storage directory with disposable paths; the historical ownership migration test is pinned to V4. Architecture/roadmap and ADR implementation notes 0001–0005 are updated. Approved uncommitted M9A/M9B production code, Compose, secret-ignore rules and V1–V4 are preserved.

Review covered each document/revision lookup, count, mutation and download: user-facing reads have tenant/identity predicates; writes additionally require persisted write roles; revision/current-pointer foreign keys include ownership; internal maintenance queries are inaccessible through HTTP. Known foreign UUID, role-change and machine tests demonstrate the supported authorization paths. Version checks occur after authorized lookup, with row locks and pending-revision uniqueness preventing overwrite races. Revision metadata is independent of response DTOs and immutable after finalization. Storage keys are generated and persisted server-side, excluded from DTOs, and never accepted as file selectors. No logs/responses expose source bytes, credentials or raw storage paths. Existing regression and upgrade tests pass; no persistent data was migrated/reset, and no real tokens/secret files were read.

Deletion intentionally retains private originals and history. Failed upload cleanup is compensating and recoverable rather than a distributed transaction; startup recovery requires the single-runtime locks. The tests simulate interrupted upload states and database/file faults, and do not claim a real power-loss or multi-instance proof. The PDF parser and storage/runtime limitations below remain material deployment constraints. The prior real Keycloak PKCE smoke tests were not repeated because authentication configuration is unchanged; these new tests do not claim cryptographic or live-provider verification.

## Limitations and next checkpoint

- One local POSIX backend process per database/storage root; no S3 or multi-instance operation.
- Bounded file buffers and synchronous structural validation; no parser process sandbox or hard CPU/heap budget for malicious PDF object graphs. Keep the API local pending a separate deployment/resource-limit review.
- No malware scanning, OCR/extraction, tags/folders, deduplication, upload idempotency key, physical purge, background cleanup, or automatic index invalidation. A response lost after commit can lead to a duplicate document if the client repeats POST; clients can inspect authorized lists/history.
- FAILED objects are retried for cleanup on restart; disk exhaustion or permissions errors need local operator correction. AVAILABLE objects are not silently discarded if storage is missing/corrupt; downloads return 503.
- No persistent local database was migrated during development. Starting the new backend against that database will apply V5 normally; back up and review first. No commits, pushes, PRs, deployments, or live Keycloak changes were made.

Next checkpoint: review and approve M10 locally, including version semantics, tombstone retention and single-process recovery. M11 durable ingestion/S3/SQS remains planned and requires separate approval; no M11 implementation is included.
