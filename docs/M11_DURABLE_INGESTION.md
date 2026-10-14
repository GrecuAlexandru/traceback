# M11 — Durable asynchronous document ingestion

## Implementation plan (2026-10-14)

Preserve the M10 synchronous, bounded validation/upload API and its single API writer
per database/private filesystem. Independent workers bypass API startup recovery and
open legacy filesystem storage read-only; distributed work ownership uses PostgreSQL
leases, never filesystem/JVM locks. Multiple API writers remain deliberately unsupported.

Append V6: immutable `storage_provider` (existing revisions explicitly LOCAL), one
preparation job per eligible revision, and durable outbox events with tenant composite
foreign keys. AVAILABLE publication and job/event creation share one transaction.
New uploads select LOCAL or S3 explicitly; no automatic file migration or old-document
backfill. S3 writes use UUID keys, conditional creation, SHA-256 and bounded single PUT.
Failed reservations keep their keys for M10 compensation/reconciliation.

The dispatcher claims due rows with SKIP LOCKED and expiring token leases, publishes
outside the transaction, then fences delivery updates by token. A crash after send and
before delivery commit produces duplicates. Workers validate a versioned opaque-ID
message against its persisted outbox/job/revision ownership, claim with document-first
row locking and a token lease, fetch bounded bytes outside the transaction, then recheck
current revision/tombstone/token/lease before committing VERIFIED. VERIFIED means only
size/checksum verified and ready for future extraction; it is never indexed/searchable.

Transient failures use bounded exponential backoff with jitter. Permanent/exhausted
failures remain visible and unacknowledged for queue DLQ redrive. Successful/obsolete
handling commits before acknowledgement; busy jobs defer delivery. Expired leases and
missing deliveries are reconciled periodically by rearming the durable event. Deletion
and supersession cancel work transactionally and fence in-flight completion. An already
authorized bounded read cannot retract bytes in flight; workers never return contents.
A database-only development retry script creates a new event generation so old deliveries
cannot execute a reset job. No public unauthenticated retry operation.

Add LocalStack without changing existing services/volumes, local bootstrap scripts,
sanitized examples, runtime role/Actuator configuration, future IAM examples and failure
integration tests using disposable PostgreSQL and AWS emulation. Run clean test, verify,
Compose checks and diff review. No parsing, chunks, embeddings, RAG, MCP or AWS deployment.


## Implemented lifecycle and contracts

The API still validates/uploads synchronously with the M10 limits and authorization;
preparation runs asynchronously. No presigned URL is issued. The roadmap's earlier
parser/chunker/presigned-URL target conflicted with this milestone's explicit scope and
has been corrected. PDFBox remains solely in the existing upload validation path; workers
invoke no PDF parser, extraction library, OCR, embeddings or LLM.

1. An authorized human ADMIN/ENGINEER reserves UPLOADING metadata in a committed
   transaction. `storage_provider` and the generated UUID key are immutable.
2. The selected adapter writes bounded validated bytes. LOCAL keeps M10 atomic promotion;
   S3 uses a single conditional `If-None-Match: *` PUT with SHA-256 and SSE-S3 encryption.
3. One publication transaction changes the revision/document to AVAILABLE, cancels older
   jobs, and creates a PENDING job and outbox event. An event-insert failure rolls all
   publication changes back; M10 compensation records FAILED and removes private content.
4. A dispatcher leases one due outbox row with `FOR UPDATE SKIP LOCKED`. Sending occurs
   outside the transaction. Recording delivery requires the same unexpired token. Queue
   failure keeps the event pending with sanitized `QUEUE_UNAVAILABLE` and bounded backoff.
5. A worker matches every message reference against its durable event, then locks the
   document before the job. It joins authoritative tenant/revision metadata, requires the
   current AVAILABLE revision of an active document, and claims an eligible due job with
   a fresh token. Busy jobs are deferred; terminal handled duplicates are acknowledged.
6. Outside the claim transaction, it retrieves bounded bytes from the persisted provider
   and verifies exact size/SHA-256. Finalization locks document then job again and requires
   the current generation/token, unexpired lease and active current revision. It commits
   VERIFIED, RETRY or FAILED; completion never changes document/revision content metadata.
7. SQS DeleteMessage occurs only after durable successful/obsolete handling. DB errors or
   failed acknowledgements leave the message for redelivery. Deletion/supersession cancels
   jobs in the same API transaction and clears their leases, fencing late completion.

An authorized content read already in flight cannot be retracted by concurrent deletion,
as with M10 downloads. Workers return no content and cannot publish a result after a
tombstone/supersession. Delayed messages for cancelled jobs perform no object read.
Historical AVAILABLE revisions remain downloadable through the same M10 tenant policy.

| Preparation state | Meaning |
| --- | --- |
| NOT_SCHEDULED | No job, e.g. a pre-V6 revision or an unsuccessful upload attempt. |
| PENDING | Durable job/event created; no successful claim yet. |
| RUNNING | Claimed under a token lease; never implies prepared content. |
| RETRY | A transient dependency failure; next claim is delayed. |
| VERIFIED | Exact bytes/size/SHA-256 checked; ready for a future approved extraction stage. |
| FAILED | Permanent content failure or exhausted processing attempts; needs inspection. |
| CANCELLED | Revision superseded or document tombstoned; cannot produce active preparation. |

`GET /api/v1/organizations/{org}/documents/{doc}/revisions/{revision}/ingestion`
returns status, attempts, sanitized errorCode, verifiedAt and processingMs. It uses the
same trusted-human/membership/tenant checks as downloads, including VIEWER read access
and generic 403/404 responses for foreign/deleted IDs. It reveals no object key, event ID,
queue receipt, storage endpoint, credentials or document contents. Existing DTOs stay
unchanged. API status is independent of M10's storage AVAILABLE status.

## Message and consistency guarantees

The compact message is exactly 187 ASCII characters:

```text
v1:<event UUID>:<job UUID>:<organization UUID>:<document UUID>:<revision UUID>
```

UUIDs must be canonical lowercase strings. Unknown versions, malformed/oversized bodies,
unknown events and reference mismatches are unacknowledged poison messages, with no
content access or guessed-tenant mutation. The queue grants internal processing capability;
it grants no public download capability. Database event ownership is authoritative, not
payload organization IDs. No filenames, content, user identities, roles or paths enter SQS.

PostgreSQL and AWS do not share an atomic commit. A crash after SQS send and before
`delivered_at` commit produces a second send after the outbox lease expires. Duplicate
messages find the same unique revision job; VERIFIED handling does not increment attempts
or create another result. Concurrent claimers serialize using document/job row locks;
concurrent dispatchers skip locked event rows. All locks are released before network I/O.

If a worker crashes after claim, its token expires and another worker can claim. The old
worker cannot finalize with a replaced or expired token. A successful completion followed
by acknowledgement failure is harmless on redelivery. Cancellation and outbox publication
may overlap; a published cancelled event still resolves to a durable no-op.

The reconciler rearms a delivered event for a PENDING/RETRY/expired RUNNING job only when
its job timestamps and last delivery are older than the recovery threshold. This repairs
lost/expired queue deliveries, including queue retention or emulator reset, without
resetting attempts or live leases. Event generations are unchanged during automatic
recovery. After a manual eligible FAILED retry, a new generation/event invalidates old
messages. Old messages are acknowledged as obsolete and cannot run the reset job.

## Failure policy and resource limits

| Setting | Current default / rule |
| --- | --- |
| API document limit | 10 MiB; existing validator permits explicit 1 byte..50 MiB configuration. |
| Worker concurrency | One received document at a time per process; separate processes claim safely. |
| Message receive | One message, five-second long poll. |
| Job/outbox lease | 120 seconds; valid configuration range 60..600 seconds. |
| SQS visibility per receive | 150 seconds, at least the configured lease. |
| Processing attempts | Five; expired crashed claims count toward this limit. |
| Processing/publish backoff | Equal jitter over half/full exponential delay, capped at 60 seconds. |
| Poison/terminal defer | One second; eight receives trigger source-queue DLQ redrive. |
| Lost-delivery recovery | 180-second age threshold, checked every 30 seconds. |
| Dispatcher batch | At most 20 events per scheduled invocation. |
| SDK call/attempt timeout | 20 / 8 seconds; connect timeout two seconds. |
| Socket timeout | S3 five seconds; SQS eight seconds, longer than the long poll. |
| S3 body read deadline | 30 seconds including request setup, checked per bounded read; abort on failure. |
| Worker DB transaction timeout | Ten seconds. |

Worker preparation is bounded byte retrieval/hash verification, so no heartbeat is needed
under the current lease. The socket timeout bounds a blocked individual read; a slow
stream can run slightly past the 30-second deadline before its next read returns. Completion
still requires a live lease. Extraction will need a separately approved duration/isolation
and lease-renewal design. Memory is bounded by the file cap and adapter buffers, rather
than proportional to an unbounded remote object. No multipart upload is used.

`CONTENT_MISSING`, `CONTENT_LIMIT` and `CONTENT_CORRUPT` are permanent. Network/provider
and unexpected storage exceptions become sanitized `STORAGE_UNAVAILABLE` retries. After
five unsuccessful/crashed claims, the job becomes `RETRIES_EXHAUSTED`. FAILED handling
never increments attempts again and leaves messages for the DLQ. Dispatcher publication
keeps retrying indefinitely with capped delay: discarding a committed event would lose work.

S3's atomic PUT cannot expose a partial object as AVAILABLE. An ambiguous PUT response may
leave a complete private object; its durable reservation supplies the cleanup key. If
compensation cannot reach PostgreSQL, retain metadata/object for API startup reconciliation.
If object cleanup fails, retain FAILED metadata and retry cleanup on API restart. No bucket
scan, historical-file migration, tombstone purge, or destructive cleanup is introduced.
Storage startup cleanup remains M10's single-writer operation; workers never execute it.

## Local setup and independent execution

Required: Docker, Java 21, and the existing local Keycloak configuration. No AWS account,
cloud credentials or LocalStack license is used. Endpoint validation accepts only local
HTTP emulator hosts (`localhost`, `127.0.0.1`, `localstack`), and the SDK uses fixed dummy
`test` credentials. Real cloud endpoints/default credential-chain activation are deferred.
The new service does not alter existing PostgreSQL/Keycloak services or volumes.

```bash
# Repository root; create .env from .env.example only if you do not already have .env.
# Preserve the existing Keycloak password/configuration.
docker compose config --quiet
docker compose up -d --no-deps --wait localstack
docker compose ps

# backend/: build the shared executable JAR.
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw verify

# API terminal; export these local settings (Spring does not read .env automatically).
export LOCAL_AWS_ENABLED=true
export LOCAL_AWS_ENDPOINT=http://localhost:4566
export DOCUMENT_STORAGE_PROVIDER=S3
# Preserve DOCUMENT_STORAGE_DIRECTORY for any existing filesystem-backed revisions.
java -jar target/traceback-backend-0.0.1-SNAPSHOT.jar

# Second terminal, same DB/endpoint/private legacy directory:
export LOCAL_AWS_ENDPOINT=http://localhost:4566
java -jar target/traceback-backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=worker
```

API binds loopback port 8080; worker binds loopback port 8081 and denies all application
HTTP routes, even if sent a bearer token. The worker profile enables AWS and a three-thread
scheduler for dispatch/consume/recovery. API and worker both may dispatch; leasing makes
this safe. For another worker instance use `--server.port=8082`. API remains one process
per database; S3 does not remove the M10 upload-recovery advisory lock. Both processes need
the initialized legacy directory; start the API first. Read-only shared POSIX storage is
required for LOCAL workers; S3-only multi-host deployment and lazy legacy adapters are
future deployment decisions, not claims of this milestone.

LocalStack 4.8.1 is pinned and initialized by `scripts/localstack/10-init.sh`: private
bucket, public-access blocking, encryption, standard ingestion queue and 14-day-retention
DLQ. The source queue retains messages four days. Tests run the same initialization script
in a disposable container. Only the new LocalStack service was started during verification;
existing PostgreSQL/Keycloak and their data were not restarted or migrated.

**Emulator limitation:** this unlicensed configuration is ephemeral; restarting/removing
LocalStack can lose synthetic S3 objects and messages. Keep valuable existing documents on
LOCAL with backups. Database recovery can republish lost messages but cannot recreate lost
object bytes. No automatic upload or migration of existing local documents occurs. Current
LocalStack persistence/licensing differs from a real durable S3 service; see
[LocalStack persistence](https://docs.localstack.cloud/aws/developer-tools/snapshots/persistence/).

## Operations and development retry

Public Actuator health hides all details. Worker readiness includes application readiness,
PostgreSQL and S3/SQS reachability; liveness reports application liveness independently of
dependency outages. Only health is exposed through HTTP by default. Micrometer records
outbox pending/publication failures, expired leases, terminal jobs, publication/delivery
counts, processing durations, retries and success/failure counts. Counters/timers are
process-local; durable database state remains authoritative across restarts. No document
IDs, contents, keys or filenames are metric labels. For local JMX inspection, explicitly
start with `--spring.jmx.enabled=true --management.endpoints.jmx.exposure.include=health,metrics`.
No remote JMX listener is enabled by this code.

```bash
curl http://localhost:8081/actuator/health/liveness
curl http://localhost:8081/actuator/health/readiness
# Inspect counts, not source content/receipts.
docker compose exec localstack sh -c 'awslocal sqs get-queue-attributes \
  --queue-url "$(awslocal sqs get-queue-url --queue-name traceback-ingestion-dlq-local --query QueueUrl --output text)" \
  --attribute-names ApproximateNumberOfMessages'
```

Use a local administrative psql session with credentials supplied by secure standard
configuration. Inspect only sanitized operational fields:

```sql
SELECT status, count(*) FROM ingestion_jobs GROUP BY status;
SELECT count(*) FROM ingestion_outbox WHERE delivered_at IS NULL;
SELECT organization_id, document_id, revision_id, attempts, error_code, updated_at
FROM ingestion_jobs WHERE status='FAILED' ORDER BY updated_at;
SELECT count(*) FROM ingestion_jobs WHERE status='RUNNING' AND lease_until<clock_timestamp();
```

After repairing the underlying failure, an administrator may run from the repository root:

```bash
psql -v ON_ERROR_STOP=1 -v org="$ORG" -v doc="$DOC" -v rev="$REV" \
  -f scripts/ingestion/retry.sql
```

The transaction locks the document, resets only a FAILED current AVAILABLE revision of an
active document, increments generation and inserts a new outbox event. Foreign/mismatched,
deleted, superseded, running, verified and cancelled selections do nothing. Database
administrative access is the trust boundary; this is not a user-facing authorization API.
DLQ redrive alone does not reset a terminal job; it must be repaired/retried first. Do not
redrive invalid payloads repeatedly. No contents or queue message bodies need to be printed.

## Future least-privilege IAM examples (documentation only)

No real IAM identity/resource is created. Substitute reviewed placeholders in a future
cloud deployment. The API needs GetObject/PutObject/DeleteObject only under its private
bucket prefix; workers need GetObject only. Both current runtime roles dispatch events,
so both need queue send permission. Worker queue receive/delete/change-visibility are
additional permissions. HeadBucket readiness needs bucket ListBucket. Queue provisioning,
public-access settings, encryption and DLQ administration belong to a separate deployment
administrator, not application identities.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {"Effect": "Allow", "Action": ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"],
     "Resource": "arn:aws:s3:::<PRIVATE_BUCKET>/documents/*"},
    {"Effect": "Allow", "Action": "s3:ListBucket", "Resource": "arn:aws:s3:::<PRIVATE_BUCKET>"},
    {"Effect": "Allow", "Action": ["sqs:GetQueueUrl", "sqs:GetQueueAttributes", "sqs:SendMessage"],
     "Resource": "arn:aws:sqs:<REGION>:<ACCOUNT_ID>:<INGESTION_QUEUE>"}
  ]
}
```

For the worker, replace the first action list with `["s3:GetObject"]` and add
`sqs:ReceiveMessage`, `sqs:DeleteMessage`, `sqs:ChangeMessageVisibility` to the queue action
list. Prefer separate database runtime permissions and filesystem read-only mounts too;
worker adapters already refuse application-level writes, but M11 does not provision separate
PostgreSQL accounts. IAM/public-access denial must be verified against real AWS before a
separately approved deployment. LocalStack ACL/public-block/encryption metadata checks
are not proof of production IAM or anonymous-request enforcement.

## Verification and remaining scope

Tests use synthetic documents, disposable PostgreSQL 17 and LocalStack 4.8.1. No real AWS,
persistent local database, personal documents or new Keycloak setup was used. Existing
organization/incident and M10 tests remain regression gates; prior real Keycloak verification
is preserved, not rerun or newly claimed here.

Coverage includes conditional private S3 writes and checksums, LOCAL/S3 compatibility,
V5→V6 preservation/constraints, publication/outbox rollback, real SDK queue errors,
concurrent dispatchers, crash-after-send duplicates, concurrent job claims, expired-token
fencing, transient/permanent errors, bounded attempts, foreign reference forgery, deletion
and supersession during read, commit and acknowledgement failure, real SQS visibility,
DLQ transfer and StartMessageMoveTask redrive, new-generation administrative retry, lost
queue-delivery reconciliation, read-only worker storage, denied worker HTTP API/readiness,
and a separately launched Java worker with live scheduling. Crash states are simulated
through durable claims/lease expiration; this is not a hardware power-loss test. Polling
uses deadlines; lease expiration is injected into disposable test rows rather than slept.

LocalStack does not establish real-AWS throughput, IAM isolation, retention timing,
rare SQS receipt/visibility races, regional durability or consistency under physical
failure. SDK failure injection covers controlled interruptions; it is not a distributed
network-chaos certification. The free local emulator's storage is ephemeral. Outbox/job
history and tombstone content are retained; retention/purge remains future work.

M12 must decide resource-isolated extraction, a distinct extraction/index state machine,
lease renewal for longer work, tenant-authorized retrieval and indexing of active sources,
and re-index/backfill policies. VERIFIED provides stable revision IDs/checksums; it implies
no chunks, text, embedding, index or search availability. Do not start M12 until review.

### Actual verification record (2026-10-14, Java 21)

Commands are from `backend/` unless marked root. Every Maven run used
`JAVA_HOME=$(/usr/libexec/java_home -v 21)`; no tests were skipped.

| Command | Result |
| --- | --- |
| `./mvnw -q -DskipTests compile` | Passed initial implementation compilation; tests intentionally not run in this compile check. |
| `./mvnw -Dtest=DocumentApiIntegrationTest,DocumentMigrationIntegrationTest,LocalDocumentStorageTest test` | Passed 34 existing document/storage/migration tests. |
| `./mvnw -Dtest=DurableIngestionIntegrationTest test` | Passed first 20 ingestion integration tests. |
| `./mvnw -Dtest=DurableIngestionIntegrationTest,IngestionMigrationIntegrationTest,DocumentApiIntegrationTest test` | Passed 54 expanded integration tests. |
| `./mvnw clean test` | Passed 106 tests, zero failures/errors/skips. |
| `./mvnw verify` | Passed 106 tests, zero failures/errors/skips; executable JAR packaged. Includes final lease-pair constraint and explicit LOCAL worker verification. |
| `docker compose config --quiet` (root) | Passed. |
| `docker compose up -d --no-deps --wait localstack` (root) | Passed; only the new emulator service created/started, then healthy. |
| `docker compose exec -T localstack /etc/localstack/init/ready.d/10-init.sh` (root) | Passed repeat initialization without resetting storage or queues. |
| `docker compose ps` (root) | PostgreSQL healthy, Keycloak running, LocalStack healthy; host ports all loopback. |
| `git diff --check` (root) | Passed. |
| `git diff --exit-code -- backend/src/main/resources/db/migration/V{1..5}*.sql` (root; equivalent explicit paths used) | Passed; historical migration contents unchanged. |

The final suite comprises 45 existing organization/incident tests, 35 document/storage/V5
migration tests (one new scoped preparation-status test), 25 durable ingestion integration
tests and one V6 migration test. The integration suite includes actual LocalStack SQS
redelivery and DLQ redrive, a separately launched scheduled worker JVM and a second worker
application context with real loopback health/API-denial requests.

No application was pointed at the persistent Compose database during implementation; V6
was applied only in disposable tests. Existing Keycloak settings, stored files, database
rows and PostgreSQL/Keycloak volumes were preserved. No commits, pushes, PRs, deployments
or paid resource provisioning were performed. Changes remain on `codex/m11-durable-ingestion`.

### Security/data review

Review confirmed that the new status route authorizes both document and revision through
existing scoped queries; worker claims compare all IDs to durable events before storage
access; SQL composite foreign keys prevent cross-tenant jobs/events; and stale generation,
lease, revision and tombstone checks fence completion. SDK/local adapters never select paths
from filenames, never expose keys/URLs and refuse worker writes. Operational errors/labels
contain only fixed codes and operation names. V1–V5, issuer/audience/human classification,
M10 immutable content and incident authorization are preserved. No new parsing/indexing or
cloud credentials are introduced. The remaining deployment/isolation/persistence limits
are listed above and are not covered by production verification claims.
