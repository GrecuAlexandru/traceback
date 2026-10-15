# Local MCP demo without local AI

The user-requested MCP checkpoint uses the persistent demo data and existing six
read-only M14 tools. It adds no application write tool, schema, public ingress or model.
MCP endpoint: `http://127.0.0.1:18081/mcp`.
Website: `http://127.0.0.1:18766`.

## Start and test

From the repository root:

```sh
python3 scripts/demo/manage.py mcp
python3 scripts/demo/mcp_client.py demo
```

The second command opens your default browser to the existing demo Keycloak realm.
Sign in as `demo-engineer` using the password in the private
`~/.local/share/traceback/demo/login.json`. Return to the terminal. No password or token
needs to be pasted into a command. An existing browser sign-in may complete automatically.
The command negotiates MCP, discovers the tools, lists your organizations, lists five
Engineering incidents, reads one incident, does a keyword search, reads a document's
metadata and resolves its exact source passage. It changes no incidents/files/chat data.

For individual normal-path tests:

```sh
python3 scripts/demo/mcp_client.py tools
python3 scripts/demo/mcp_client.py organizations
python3 scripts/demo/mcp_client.py incidents --organization YOUR_ORGANIZATION_UUID
python3 scripts/demo/mcp_client.py incident --organization YOUR_ORGANIZATION_UUID --incident YOUR_INCIDENT_UUID
python3 scripts/demo/mcp_client.py search --organization YOUR_ORGANIZATION_UUID --query database
```

Use IDs returned by the previous command. Each invocation obtains a fresh short-lived
token through Code + S256 PKCE and discards it on exit; tokens are not saved. The
`--headless` flag is for unattended verification of the existing synthetic account using
installed Playwright; the normal command uses your browser and a loopback callback on
port 8767. No browser storage/trace export or dependency installation is performed.

`python3 scripts/demo/manage.py up` preserves the last selected mode. To restore local
chat/embeddings/processing explicitly, use `python3 scripts/demo/manage.py ai`. That
returns to the original guarded AI startup and disables MCP. Existing source bytes,
revisions, indexes, conversation history and Keycloak identities remain on their named
volumes in either mode. No model weights are deleted. Mode switching restarts the
frontend/identity/API loopback group, so sign in again if your browser session expires.

## What is running

MCP mode keeps PostgreSQL, Keycloak, the API and the website. It stops the worker,
embedding runtime, chat runtime and demo LocalStack; chat/indexing/AWS scheduling are
explicitly disabled. Upload metadata/originals remain durable; processing resumes in AI
mode. Keyword search uses existing PostgreSQL full-text indexes and needs no model.
Semantic search is unavailable and hybrid can explicitly fall back to keyword results.
Original M17 PostgreSQL/LocalStack are left running because their test data is ephemeral.
Do not stop/remove their data services or use `down -v` to free memory.

## How the integration works

MCP standardizes tool discovery and calls between a client and an application server.
The server can work with a simple script; it does not require a language model.

1. The client signs you in through Keycloak using Code + PKCE, requesting the exact MCP
   resource in both authorization and token requests.
2. Keycloak issues a short-lived token whose audience is the MCP endpoint URI. This is a
   different audience from the website's `traceback-api` token.
3. The client sends `initialize`, then `notifications/initialized`, and `tools/list` over
   Streamable HTTP JSON-RPC. The response describes names, descriptions and input schemas.
4. The client sends `tools/call`, such as `traceback_list_incidents` plus an organization
   UUID and pagination. A language-model client could choose that call from a user request;
   this script chooses it directly.
5. Spring Security checks signature, issuer, expiry, MCP audience and trusted human
   identity. Existing services and SQL check current organization membership and ownership.
6. The server returns structured JSON. A script prints it; an external AI client may use
   it to compose an answer. Source text remains untrusted content and grants no authority.

Traceback's tools list organizations; search knowledge; read document metadata; resolve
an exact source; list incidents; and read one incident. They do not create/update/delete
records, run shell commands, call a model, or perform remediation. Authentication may
create ordinary Keycloak login sessions; MCP calls do not mutate application records.
The implementation negotiates protocol 2025-11-25, not the newer 2026 lifecycle profile.

## Local wiring and authentication

`compose.demo.mcp.yaml` overlays the existing persistent Compose configuration. The API
binds `127.0.0.1:18081` inside the shared loopback namespace. A small byte-preserving Node
forwarder reuses the installed frontend image for Docker's existing host mapping:

`host 127.0.0.1:18081 -> Docker namespace :8080 -> API 127.0.0.1:18081`

It preserves Host and all HTTP bytes, has bounded connections and inactivity timeouts,
and logs no traffic. The website BFF talks directly to the API's loopback port. Published
host ports stay loopback-only. MCP's listener/Host/Origin/forwarding/authentication guards
are unchanged; no CORS wildcard or proxy trust setting is added.

Keycloak enables the accepted experimental `resource-indicators` feature. Setup adds
only the `traceback-mcp` resource client and `traceback-demo-mcp` public PKCE client, with
an exact callback and dedicated human/audience mappers. Human password grants, service
accounts, implicit/token-exchange/device grants remain disabled on the MCP login client.
The website client, existing demo user's subject/password and application memberships are
not replaced. For the existing realm with no administrator, the offline Keycloak bootstrap
command creates a random temporary setup administrator while Keycloak is stopped; setup
removes that administrator after adding clients. Credentials exist only in process memory.
No realm export/reimport/reset is performed. The private import descriptor gains only
these clients for a future new installation. No external client registration configuration
is changed on your computer.

Other MCP applications need a compatible pre-registered PKCE client/redirect URI. Do not
assume arbitrary clients' automatic registration or OAuth helpers work with this local
profile; the supplied native client is the verified starting point. No bearer token is
written into a shared configuration file.

## Verification

Completed locally on 2026-10-15:

| Exact command/check | Actual result |
| --- | --- |
| `python3 scripts/demo/manage.py mcp` | PASS; persisted demo identity/data retained, dedicated clients configured and temporary setup administrator deleted; MCP ready |
| `python3 scripts/demo/mcp_client.py demo --headless` | PASS twice; real browser Code + PKCE with resource indicators, initialization/notification, six-tool discovery, all six read calls including keyword/source resolution |
| `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -q -Dparser.image.skip=true -Dtest=McpIntegrationTest,McpBoundaryTest,McpJwtValidationTest,McpToolLimitTest test` from `backend/` | PASS: 25 tests, zero failures/errors/skips, including official SDK + isolated real Keycloak. Only nested parser image building disabled; no model calls |
| `python3 -m unittest discover -s scripts/demo -p 'test_*.py'` | PASS: 13 tests, zero skips |
| `node --test scripts/demo/dataset.test.mjs scripts/demo/loopback-proxy.test.mjs` | PASS: four tests, zero skips |
| Both demo Compose files `config --quiet`, root `docker compose config --quiet`, `git diff --check` | PASS |
| Website browser Code + PKCE / BFF organization listing | PASS after MCP-mode switch; original website client remains usable |
| Application inventory fingerprint before/after native demo | PASS: incident/document versions, source revision checksums and saved turn status fingerprints identical |
| Public resource metadata / unauthenticated `/mcp` | HTTP 200 / HTTP 401 as intended |
| Model/worker/emulator inspection | Demo worker, both model runtimes and LocalStack stopped; PostgreSQL/Keycloak/API/frontend retained |

Initial setup hit a local marker-file API error after adding clients; marker writing was
corrected and matching existing dedicated clients were verified without resetting the realm.
The initial synthetic browser callback interception failed to catch a redirect; verification
now uses the same real loopback callback server as interactive login. The JWT client also
normalizes the permitted string/array audience representation before requiring exactly the
MCP resource. Final complete runs above passed after those corrections.

No Java/frontend application implementation or migration changed. Full Maven/frontend
regression suites were not rerun for this local configuration/client checkpoint. No cloud,
external AI client installation or automatic-registration compatibility is claimed.
