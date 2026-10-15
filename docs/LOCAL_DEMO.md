# Persistent local demo

This is the user-requested synthetic demo checkpoint, separate from the disposable M17
verification project. It reuses the existing application images and installed model
weights. No AWS resource, paid provider, production data or schema migration is involved.

Open **http://127.0.0.1:18766**. The account is `demo-engineer`; its randomly generated
password is in `~/.local/share/traceback/demo/login.json` (private directory, mode 0700;
login file mode 0600). Open that file locally to sign in. Credentials are never printed
by the tooling or committed. The demo uses its own Keycloak realm and persisted identity.

The primary organization is **Northstar Engineering · Demo**. It contains 24 incidents
across OPEN, INVESTIGATING and RESOLVED states; 17 knowledge documents; and three private
conversations with real locally generated answers and live citations. The corpus covers
database pools, payments, queues, authentication, deployments, caching, webhooks, disk
pressure, DNS, tenant isolation, indexing and rate limits. Files include 15 Markdown
runbooks/postmortems, one text handover checklist and one PDF quick reference. The database
runbook includes a second revision. Incident descriptions contain fictional severity,
service ownership, impact, diagnosis, response and preventive actions; API creation times
are real creation times, not fabricated historical timestamps.

**Northstar Sandbox · Demo** has four incidents and two documents, including a revised
runbook, for organization switching and known foreign-ID checks. Total: 28 incidents,
19 documents, 21 immutable source revisions, three conversations and two organizations.
All content is fictional. Membership is provisioned through the ordinary human API;
conversations are private to this demo account. No shared-access or role shortcut is added.

For the user-requested low-memory MCP mode and a browser-authenticated test command,
see [Local MCP demo](LOCAL_MCP_DEMO.md).

## Start, stop and reseed

From the repository root:

```sh
python3 scripts/demo/manage.py up
node scripts/demo/seed.mjs
python3 scripts/demo/manage.py status
python3 scripts/demo/manage.py stop
```

`up` generates the private identity fixture once, validates Compose, starts dependencies,
stops the singleton API/worker before applying existing migrations through the separate
image command, reapplies reviewed restricted grants, and starts API/worker. Runtime roles
cannot run migrations. Repeated `up` preserves identity and data; startup never resets a
realm, volume or database. The standard images `traceback-{api,worker,frontend,pdf}:m17`
must already be built (see M17 instructions). The installed model volumes
`traceback_m12_models` and `traceback_m13_models` must exist; no weights are downloaded.
On Docker Desktop the daemon-side socket defaults to `/run/host-services/docker.proxy.sock`;
Linux uses `/var/run/docker.sock`. Override `DEMO_DOCKER_SOCKET`/`DEMO_DOCKER_GID` if needed.
Existing PDF isolation and singleton ownership remain in effect.

The seed script signs in with real Code + PKCE, uses the normal BFF with CSRF headers,
and writes through authorized application APIs. It runs serially, leaves existing matching
records untouched, and creates only missing reserved demo titles. Run one seeder at a time;
renaming/deleting demo records makes those reserved titles eligible for creation again.
The revision demonstration appends only to an untouched original fixture. A deterministic
chat idempotency key prevents repeated generation, including after an ambiguous response;
failed turns are not retried with a new key. User edits are never overwritten by SQL.
The script checks actual ingestion/indexing, downloads every revision and compares its
SHA-256, exercises three search modes, verifies foreign-ID/CSRF denial, and resolves live
citations. Screenshots stay in `/tmp/traceback-demo-evidence`; no authentication trace or
browser storage export is written. A sanitized summary is stored beside the private fixture.

## Persistence

`compose.demo.yaml` owns these project-scoped named volumes:

| Volume | Durable contents |
| --- | --- |
| `traceback-demo_database` | Incidents, organizations/memberships, document metadata/revisions, extraction artifacts, chunks/vectors, outbox/jobs and conversation history/evidence |
| `traceback-demo_legacy` | Private original uploaded bytes, including prior revisions; API read/write, worker read-only |
| `traceback-demo_identity` | Keycloak realm, stable user subject, signing identity and account settings |

New uploads explicitly use the existing **LOCAL** provider. LocalStack supplies disposable
queues, not durable document storage. PostgreSQL outbox/leases reconstruct pending work
through existing recovery; already indexed sources and citations do not depend on emulator
objects. Existing S3/LOCAL records in other projects are never changed. All published
ports are loopback-only: frontend 18766, Keycloak 18181, backend 18081. PostgreSQL and
emulator ports are not published. Private Docker-network database trust is a local demo
assumption; this is not a production topology or backup solution.

Logging out, changing browser sessions or recreating containers preserves the dataset.
A frontend restart clears its in-memory session and requires sign-in again, as designed.
`restart: unless-stopped` restarts services when Docker returns unless explicitly stopped.
Use the management `up` command after stopping. Keep the private fixture directory and
named volumes; deleting them or Docker's disk removes identity/data. The M17 project remains
separate and disposable. Never use `down -v` for this demo.

Verify container recreation without reseeding:

```sh
python3 scripts/demo/manage.py stop
python3 scripts/demo/manage.py up --recreate
node scripts/demo/seed.mjs --verify-only
```

The final command only reads application state after signing in; it creates no fixtures
and generates no answers. It verifies original downloads/revisions, indexes, search,
conversations and citations after recreation. Keycloak login itself creates a login session.

## Chat memory recovery

A real demo request on 2026-10-15 failed with `MODEL_UNAVAILABLE` after about 80 seconds.
The chat container's cgroup recorded `oom_kill=1` (with no cgroup memory-limit hits), and
Docker marked it OOM-killed. The Docker VM exposes about 7.75 GiB total, while the 7B
model alone was observed near 4.8 GiB peak. Both full stacks were running. This is an
observed memory-pressure failure, not a missing model or an MCP problem.

For this small VM, run one application/model stack. The redundant M17 worker, API,
frontend, Keycloak and two Ollama containers were stopped **without removing containers**.
Its PostgreSQL (tmpfs) and LocalStack (ephemeral objects/queues) were kept running to
preserve their data. Keycloak's writable container filesystem and all model volumes were
retained. The persistent demo stays running at port 18766.

If the duplicate stack is started again, this is the narrowly scoped recovery command:

```sh
docker stop --time 35 traceback-m17-worker-1 traceback-m17-api-1 traceback-m17-frontend-1 traceback-m17-keycloak-1 traceback-m17-chat-ollama-1 traceback-m17-ollama-1
```

Do not include M17 PostgreSQL or LocalStack in that command. `manage.py up` now checks
Docker's memory before changing services: below 12 GiB, it refuses startup when competing
M17 application/identity/model services are running. It never stops other projects itself.
12 GiB is a conservative startup allowance, not a tested guarantee for arbitrary concurrent
workloads. Avoid memory-heavy Docker builds or additional model runtimes during local chat.
No model was changed/downloaded, no timeout increased, and no VM resources were changed.

A failed turn stays FAILED with its original replay key. **Check saved turn** reads that
saved outcome; it does not regenerate. After recovery, click **Dismiss saved retry**, then
**Send message** to intentionally create a new turn, or start a new conversation. The
recovery check uses its own diagnostic conversation and does not retry/edit the user's
failed turn.

Recovery verification: `node /tmp/traceback-demo-evidence/chat-recovery-check.mjs` passed
with two real ANSWERED turns in the affected organization (38 and 42 seconds), one and
three live citations respectively, and stable assistant IDs on same-key replay. The
original failed conversation's message IDs/status/error codes remained identical. The
cgroup OOM-kill counter stayed at one (no new kill). The diagnostic conversation was
soft-deleted after verification. Only local fixtures/authorized source retrieval were
used; no credentials or source text were logged. The updated management tests passed
all eight cases with zero skips, the three dataset tests passed, both Compose configs
validated, and `git diff --check` passed. No Java/frontend application code changed;
their regression suites were not rerun for this operational/tooling correction.

## Verification record

Completed locally on 2026-10-15:

| Command/check | Result |
| --- | --- |
| `python3 scripts/demo/manage.py up` | PASS; private identity initialized, V1–V8 applied with the separate migration command, reviewed runtime grants applied, healthy services |
| `node scripts/demo/seed.mjs` | PASS; repeat run created no duplicates; 28 incidents, 19 documents, 21 revisions, 19 published index generations, 140 chunks, three ANSWERED conversations |
| `python3 scripts/demo/manage.py stop` then `python3 scripts/demo/manage.py up --recreate` | PASS; six checked service container IDs changed; exact counts, incident/message IDs, revision checksums and application identity fingerprint unchanged |
| `node scripts/demo/seed.mjs --verify-only` | PASS after recreation, without reseeding or regenerating; real PKCE login, all current/historical downloads, three search modes, foreign incident/document ID 404s, missing-CSRF 403, all live citations, four populated screens |
| `python3 -m unittest discover -s scripts/demo -p 'test_*.py'` | PASS, five tests, zero skips |
| `node --test scripts/demo/dataset.test.mjs` | PASS, three tests, zero skips |
| Demo Compose `config --quiet`, root `docker compose config --quiet`, `git diff --check` | PASS |
| `python3 scripts/demo/manage.py status` | PASS; all demo services running, applicable health checks healthy |

The first browser check had a script-only heading/route mismatch; the script was corrected
against the actual Knowledge Base/AI Assistant screens and rerun successfully. Final
screenshots were captured after populated content loaded and inspected visually. No
application UI code was changed. Local model answers retain existing quality limitations:
for example, the database answer gave useful mitigation but failed to state the corpus's
explicit pool limit of 20. Citation availability/provenance checks passed; semantic answer
correctness was not asserted or silently edited.

Focused deterministic/configuration checks can be repeated with:

```sh
python3 -m unittest discover -s scripts/demo -p 'test_*.py'
node --test scripts/demo/dataset.test.mjs
DEMO_DIRECTORY="$HOME/.local/share/traceback/demo" docker compose --env-file /dev/null -p traceback-demo -f compose.demo.yaml config --quiet
```

The Java and frontend implementation are unchanged; these checks supplement real local
API/model/recreation verification and do not claim a fresh Maven/frontend regression run.
The next checkpoint is review of this populated local demo; cloud prerequisites remain
unchanged and no additional milestone is started.
