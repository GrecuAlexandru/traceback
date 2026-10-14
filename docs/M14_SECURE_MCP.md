# M14 — Secure read-only MCP

**Status:** Implemented and verified locally on 2026-10-14; M14 user review pending. No deployment approval.
The M14 request approves M9A–M13 locally. All pre-existing uncommitted M13 work is
preserved. Only M14 and the requested M13 citation-marker correction are in scope.
No migration, durable MCP state, frontend, autonomous agent or cloud deployment.

## Architecture and protocol profile

`mcp` is an adapter in the existing Java 21 / Spring Boot 4.1.1 modular monolith.
Spring AI's 2.0.1 BOM manages `mcp-spring-webmvc`; its official Java SDK dependency
is 2.0.0. `mcp-json-jackson3` is explicitly aligned to 2.0.0 because Spring AI's BOM
does not manage that artifact. Spring AI Ollama remains 2.0.1. No server starter or
automatic tool scanner is enabled. [ADR-0009](DECISIONS/0009-stateless-read-only-mcp.md)
records the permanent boundaries.

The SDK handles initialization, capability negotiation, ping, tools/list and tools/call.
Its supported protocol versions are **2025-03-26, 2025-06-18, 2025-11-25**. Compatible
clients negotiate 2025-11-25; an unsupported initialization version receives that
version as an alternative. Unsupported `MCP-Protocol-Version` HTTP headers receive 400.
A missing version header uses the compatible 2025-03-26 behavior. This is the
[2025 Streamable HTTP profile](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports),
not an implementation of the newer 2026-07-28 discovery/lifecycle requirements.
The [SDK roadmap](https://github.com/modelcontextprotocol/java-sdk/blob/main/ROADMAP.md)
places that newer revision on a later SDK line; upgrading the existing stack is deferred.

POST `/mcp` accepts one UTF-8 JSON-RPC envelope with `Accept: application/json,
text/event-stream` and `Content-Type: application/json`. Responses are JSON, not a
persistent SSE connection. GET and DELETE receive authenticated 405; no session is
created or retained, and caller-supplied session IDs are ignored. Notifications receive
202 without state changes. Cancellation cannot select another user's request.
Resources, prompts, sampling, elicitation, server-initiated requests, resumable streams,
subscriptions, downstream tools and chat generation are absent.

A pre-authentication boundary filter validates Host/Origin and serves configured metadata
before Spring Security 7.1's automatic metadata handler. A separate guard after authorization
sets a server-only original-JWT attribute; the transport requires it, preventing encoded
URL aliases from entering through the REST chain. Only the root servlet context is supported.
A narrow HTTP envelope guard rejects oversized bodies, duplicate keys, trailing JSON,
batches and malformed envelopes with sanitized errors before entering the SDK. Parse
errors use -32700, invalid requests -32600, unsupported methods -32601 and malformed
protocol parameters -32602. The SDK returns -32602 for unknown tools. Argument or
business failures return MCP `isError:true`, a fixed structured error code, and matching
JSON text content. No exception messages, storage diagnostics or raw provider bodies
are returned. SDK server logging is disabled because default diagnostics may echo
client metadata; Micrometer supplies operational visibility.

## Authentication, OAuth and identity

An earlier dedicated SecurityFilterChain matches the configured MCP path (including
its descendants) and its protected-resource metadata path. It validates each Bearer
JWT independently: RS256/JWK signature, configured issuer, timestamps, mandatory
unexpired `exp`, and the exact configured resource audience. Human access additionally
requires the existing trusted `traceback_principal_type=human` policy. Valid machine
tokens receive 403; missing/invalid/wrong-resource/expired tokens receive 401.

The canonical URI, such as `http://127.0.0.1:8080/mcp`, is both `resource` and required
`aud`. `traceback-mcp` is the dedicated Keycloak resource-client ID. It is not accepted
as a substitute for the resource URI. REST keeps the separate `traceback-api` audience
and its existing Boot-managed decoder. An MCP-only token cannot access authenticated
REST routes, and a REST-only token cannot access MCP. A token deliberately containing
both audiences is valid for both resources, so issuer configuration must restrict that
issuance. MCP never forwards a token to inference or another service.

GET `/.well-known/oauth-protected-resource/mcp` is public when MCP is enabled, with
Host/Origin validation and no-store. It advertises the configured canonical resource,
Keycloak issuer in `authorization_servers`, and header-only bearer authentication.
401 responses include `WWW-Authenticate: Bearer resource_metadata="<metadata URL>"`.
Metadata never trusts Host or forwarded headers. Keycloak's OIDC discovery supplies
authorization/token/JWK endpoints. PostgreSQL membership roles, rather than OAuth
scopes, govern these read tools; metadata does not advertise fictional OAuth scopes.

Clients send the canonical `resource` parameter in **both** authorization and token
requests, use Code + S256 PKCE and validate state and authorization-response issuer.
[Keycloak 26.8 guidance](https://www.keycloak.org/securing-apps/mcp-authz-server)
requires enabling experimental `resource-indicators` to bind tokens to resource URLs.
The approved test instance enables this feature and rejects unknown resource targets.
No fallback that ignores resource indicators is implemented or claimed compliant.
Pre-registration is the tested client registration method; DCR, CIMD, automatic browser
OAuth and every external client's OAuth helper have not been universally verified.

The transport captures the authenticated **original Jwt object** in an immutable SDK
context before executing a tool on its own virtual thread. Tools never reconstruct a
JWT or rely on SecurityContextHolder being propagated to another thread. Existing
services receive that object and check trusted human identity plus current PostgreSQL
membership. Resource reads also apply tenant/identity predicates inside SQL. Session
headers, tool arguments, email and token roles confer no additional authority.
Membership revocation takes effect on subsequent calls with the same token. The usual
short authorized-snapshot limitation applies to a response already in flight.

## Explicit read-only catalog and schemas

Registration order is fixed. All schemas are objects with `additionalProperties:false`;
unknown identity/role/path/URL arguments are rejected. All six tools advertise read-only,
non-destructive, idempotent, closed-world hints. These are hints, not access controls.
No tool calls a write service or an LLM.

| Tool | Input properties; required unless marked optional | Output |
| --- | --- | --- |
| `traceback_list_organizations` | None | `organizations:[{id,role,untrustedContent:{name}}]`, `truncated`; bounded database read of 101 rows, returns first 100 |
| `traceback_search_knowledge` | organizationId UUID; query 1..256 code points and M12 lexical rules; mode keyword/semantic/hybrid; limit integer 1..10 | requestedMode, effectiveMode, degradationCode, results with source, keywordRank, semanticRank and fusion/native score |
| `traceback_get_document` | organizationId, documentId UUIDs | id, organizationId, status, version, createdAt, modifiedAt, currentRevision and untrustedContent.title |
| `traceback_get_source` | organizationId, documentId, revisionId, generationId, chunkId UUIDs; configuration lowercase SHA-256 hex | exact source metadata and bounded untrustedContent |
| `traceback_list_incidents` | organizationId UUID; page integer 0..10000; size integer 1..20; optional status from IncidentStatus | items, page, size, totalElements, totalPages, hasNext |
| `traceback_get_incident` | organizationId, incidentId UUIDs | id, organizationId, status, version, createdAt, untrustedContent and descriptionTruncated |

UUID strings must have canonical 36-character form (case-insensitive); shorthand UUIDs,
coercion from strings/fractions to integers, nulls and unknown properties fail validation.
The existing search service additionally enforces at most 16 lexical terms, a letter or
digit, and no control characters. Mode/status values are case-sensitive enums.

Source metadata consists of documentId/revisionId/generationId/chunkId/configuration,
pageStart/pageEnd and ordinal. `untrustedContent` contains title (200 code points),
section (200) and excerpt (600). Search uses M12's existing keyword/cosine/RRF engine
with authorization before embeddings and inside retrieval SQL before ranking. Hybrid
fallback is explicit; semantic failure returns UNAVAILABLE. Exact source resolution
reuses M12/M13's resolver through `SourceService`, requiring membership, active document,
current available revision, INDEXED generation, current published head, and every exact
source identifier/configuration. Foreign, superseded, deleted and unpublished sources
return NOT_FOUND within an authorized organization. Missing membership returns
ACCESS_DENIED independently of the supplied resource ID.

Document revision metadata includes only id, revisionNumber, status, mediaType,
fileSize and createdAt. Original filenames, uploader IDs, checksums, storage providers,
S3 keys and local paths are excluded. Deleted documents cannot be read. Incident titles
are at most 200 code points; descriptions are at most 600 in lists and 4,000 in item
reads, with explicit descriptionTruncated. Original stored data is unchanged. The
organization-list overload adds database pagination while preserving the REST list.
No JPA entity is serialized.

Every successful result supplies both structuredContent and matching JSON TextContent
for older clients. Error codes are INVALID_ARGUMENTS, ACCESS_DENIED, NOT_FOUND,
UNAVAILABLE, BUSY, TIMEOUT, CANCELLED and RESULT_LIMIT. No private resource existence
is disclosed across tenants. A too-large result fails as a whole without partial data.

## Untrusted data and citation correction

Titles, names, sections, incident descriptions and source passages are nested under
`untrustedContent`; source IDs and eligibility come from authoritative database fields.
Content can include fake system roles, role delimiters, malicious Markdown and invented
citation references. It remains inert data. No instructions, shell command, URL, tool
choice or permission are derived from it. Legitimate source text is preserved rather
than rewritten into a misleading source. Prose markers such as `[S999]` cannot resolve
a source: get_source requires the complete database-validated reference tuple.

M13 answer validation now reserves S+number citation markers, rejects malformed/grouped/
lowercase/unclosed markers, linked citation markers and unsupported `source:`/`citation:`/
provider citation dialects, and preserves normal Markdown links, headings, [SRE]/[Service]
and engineering array notation. Valid assigned `[S1]` markers remain supported. This
validates citation syntax/provenance, not semantic support. External AI clients still
need their own prompt-injection defenses; authorization never depends on those defenses.

## Limits and operational behavior

| Configuration (`traceback.mcp.*`) | Default / allowed range |
| --- | --- |
| enabled | false; environment alias MCP_ENABLED |
| endpoint | /mcp; one dedicated top-level path; MCP_ENDPOINT |
| resource / audience | http://127.0.0.1:8080/mcp; must match each other and endpoint; MCP_RESOURCE / MCP_AUDIENCE |
| allowed-hosts | localhost,127.0.0.1,[::1]; loopback hosts only |
| allowed-origins | empty: native/no-Origin clients only; explicit exact loopback origins may be configured |
| max-request-bytes | 16384; 1024..65536, including unknown-length/chunked bodies |
| max-result-bytes | 65536; 4096..131072, complete serialized CallToolResult including duplicated JSON text |
| concurrent-requests | 8; 1..32, immediate 429 at capacity |
| concurrent-tools | 4; 1..concurrent-requests, immediate BUSY at capacity |
| requests-per-minute | 120; 1..600 process-wide fixed window; 429 + Retry-After |
| tool-timeout-seconds | 10; 1..30 per invocation |

All properties can be overridden with Spring's normal configuration binding. The listener
must bind to loopback; MCP startup rejects `server.address=0.0.0.0` or a worker runtime.
Host must be one allowed hostname with the actual listening port. Origins are absent or
exactly allowlisted; `null`, arbitrary websites and duplicate Origins fail closed. Forwarded
and X-Forwarded-* headers are refused. No wildcard CORS is enabled. Body compression is
not supported. No tunnel or public endpoint is configured.

The process-wide rate budget has no user/session-keyed cache or unbounded label state.
Capacity remains occupied until an interrupted/timed-out operation really exits, preventing
repeated slow calls from creating unbounded work. Late output is discarded; cancellation
notifications cannot cancel other users. Database/provider adapters retain their existing
own operation bounds. An uncooperative dependency can exhaust slots until it recovers;
this is fail-closed admission, not a promise that Java can forcibly stop arbitrary I/O.
Limits are per process; distributed enforcement is future deployment work.

Micrometer: traceback.mcp.http.active, http.denials (fixed reason), tools.active,
tools.calls and tools.duration (fixed registered tool/outcome), tools.denials. Document,
organization and user IDs, raw queries and content never become metric labels. Actuator
remains health-only over HTTP; no public metrics endpoint is added.

## Reproducible local startup and client verification

The existing Compose Keycloak has **not** been changed. Enabling MCP against it requires
separately approved `resource-indicators` startup and additive client configuration.
Do not simply enable MCP and assume existing REST tokens will work.

`dev/keycloak/m14-test-realm.json` is a sanitized reproducible descriptor. It defines a
separate traceback-m14-test realm, traceback-mcp resource client with resource_url,
MCP and REST public PKCE clients, and a machine negative-test client. Human mappers
are dedicated access-token mappers; human clients disable service accounts, password,
implicit, exchange and device grants. No shared user attribute grants human identity.
The test fixture emulates browser cookie handling for the exact loopback issuer
(Java's CookieManager otherwise withholds Keycloak's Secure authentication cookies over
HTTP loopback). It never follows a login redirect or forwards cookies to another origin.
It injects random ephemeral passwords/machine secret and synthetic users
only into its disposable import; credentials/tokens never enter reports. A short-lived
client derived from the same PKCE policy supports the expired-token transport test.

Run from backend/ with Java 21 and Docker; port 18084 must be free:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw \
  -Dtest=McpIntegrationTest,McpBoundaryTest,McpJwtValidationTest,RagContextTest test
```

This starts disposable loopback Keycloak 26.8 with experimental resource indicators,
disposable pgvector PostgreSQL and a real HTTP backend. It performs Code + S256 PKCE
login for two synthetic humans, sends resource in both OAuth requests, checks state/iss,
obtains a machine token, and initializes the official Java SDK Streamable HTTP client.
The client lists tools and invokes all six; raw HTTP probes supplement protocol failures.
Existing persistent Compose databases, Keycloak and all volumes stay untouched. Test
search/indexing uses deterministic mocked embeddings; this is protocol/auth/SQL evidence,
not a repeated real-model quality evaluation.

For a separately approved local runtime, configure the issuer and exact resource first:

```bash
# backend/, approved Keycloak resource/client setup and disposable/demo data already prepared
MCP_ENABLED=true MCP_RESOURCE=http://127.0.0.1:8080/mcp \
  JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw spring-boot:run
```

Do not run this against an unmigrated persistent database just for verification; use the
disposable test command above. No live startup against persistent data was performed here.

### External AI client example: VS Code

After obtaining an access token through an approved pre-registered PKCE flow, use a
runtime secret input rather than committing a token to `.vscode/mcp.json`:

```json
{
  "inputs": [{"type":"promptString","id":"traceback-token","description":"Short-lived MCP access token","password":true}],
  "servers": {
    "traceback": {
      "type":"http",
      "url":"http://127.0.0.1:8080/mcp",
      "headers":{"Authorization":"Bearer ${input:traceback-token}"}
    }
  }
}
```

Use the configured canonical address consistently. Refresh expired tokens through the
same PKCE flow. This configuration is a client setup example; VS Code's automatic OAuth
registration/refresh flow is not part of the actual verification. Native HTTP clients
normally omit Origin. The [VS Code configuration reference](https://code.visualstudio.com/docs/agents/reference/mcp-configuration)
documents this extension-host format. Interactive inputs are not forwarded to the newer
Agent Host; this example targets the extension-host client, not that portable configuration.
A browser-based Inspector frontend also needs a separately reviewed CORS setup; the Origin
allowlist alone does not enable CORS or authenticated browser preflights. The tested native
SDK path needs neither browser CORS nor a public tunnel.

## Verification record

Commands ran with Java 21 from `backend/`, except the root commands explicitly noted.
All final suites passed with **221 tests, zero failures, errors or skips**: the prior
196-test baseline plus 24 MCP tests and one citation-dialect regression test. Real local
chat/embedding quality evaluations are opt-in and were not repeated; no tests are reported
as skipped to hide that distinction.

| Exact command | Actual result |
| --- | --- |
| `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./backend/mvnw -f backend/pom.xml -q -Dtest=McpIntegrationTest,McpBoundaryTest,McpJwtValidationTest,McpToolLimitTest,RagContextTest test` (root) | Checkpoint pass: 30 tests, zero failures/errors/skips; subsequent viewer/alias cases are included in both final full runs below. |
| `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw clean test` | Final PASS: 221 tests, zero failures/errors/skips; 1:22. |
| `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw verify` | Final PASS: 221 tests, zero failures/errors/skips; 1:20; executable application JAR packaged. |
| `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./backend/mvnw -f backend/pom.xml dependency:tree '-Dincludes=org.springframework.ai:*,io.modelcontextprotocol.sdk:*'` (root) | PASS: Spring AI modules 2.0.1, one official MCP core/JSON SDK version 2.0.0. |
| `docker compose config --quiet` (root) | PASS; existing configuration unchanged. |
| `docker compose ps` (root) | Persistent PostgreSQL/Keycloak/LocalStack and previously approved model runtimes running, all published ports on loopback. |
| `git diff --check` (root) | PASS. |
| `git diff --exit-code -- backend/src/main/resources/db/migration compose.yaml evaluations/m12` (root) | PASS: tracked historical migrations, Compose and M12 evaluation unchanged. Pre-existing untracked M13 V8 is preserved; M14 adds no migration. |

The actual MCP integration suite has **16 real-HTTP tests**. The official SDK client
initializes with 2025-11-25, discovers the ordered six-tool read-only catalog, and invokes
all six tools with a real Keycloak human token. Table counts remain unchanged by those
calls. Public resource metadata is fetched and checked against the actual issuer/resource.
Code + S256 PKCE is performed for two synthetic human users; service-account issuance is
separate. `resource` is sent at both OAuth steps, token audience contains only the canonical
MCP URL, and an unknown resource target is rejected by Keycloak.

Real HTTP assertions cover missing/malformed/wrong-audience/invalid-signature/expired
credentials; valid-audience machine denial; independent REST/MCP audiences; foreign
organization/document/revision/generation/chunk/incident IDs; VIEWER reads; revocation
between calls and during retrieval; deleted/superseded/unpublished sources; 24 concurrent
alternating-user calls sharing the same session header; protocol negotiation and errors;
strict input/body limits; Origin/Host/forwarded-header rejection; encoded route aliases;
malicious prompt/Markdown/citation prose remaining inert; bounded incident output; and
uncooperative timeout capacity retention. No session ID is issued.

Six boundary tests cover disabled/worker absence, shared fixed-window rate admission,
concurrent HTTP capacity and release, unknown-length body bounds and unsafe configuration.
One cryptographic decoder test independently varies issuer, audience, expiry and signature
against controlled JWKs. One output-limit test verifies that the serialized cap includes
both structured data and text fallback, with no partial private content on failure. The
citation regression rejects unsupported markers and preserves Markdown/engineering syntax.
All prior REST/document/search/chat, migration, storage and ingestion regressions also pass.

Intermediate failures were corrected before final verification: dependency-coordinate/BOM
alignment, test fixture imports/lifecycle, container import permissions, Java's Secure-cookie
behavior on HTTP loopback, Spring Security's automatic metadata interception and servlet
principal/filter ordering. The first full run passed 220 tests; the final alias check brings
both final required runs to 221. Maven's existing optional-repository metadata 401 warnings
and Mockito instrumentation warnings were non-fatal. Synthetic test log scans found no
JWT-shaped material; no access token, generated credential, private document text or raw
sensitive tool response was intentionally logged or saved.

## Security/data review and remaining limitations

Reviewed the registered handlers and service paths, dedicated/fallback security chains,
server-only identity attribute, original Jwt context, membership SQL, source eligibility,
DTO serialization, bounds, timeout capacity retention, fixed metric labels and safe errors.
No business query was duplicated in MCP. The sole organization-query extension adds database
pagination to the existing identity-scoped query; REST's unpaged behavior remains intact.

M14 files: `mcp/` configuration/settings/security/boundary/protocol/tool adapter;
`search/SourceService`; bounded organization-list overload; the REST chain's explicit
ordering; application MCP/logging settings; Spring AI BOM/transport/SDK JSON dependencies;
M13 RagContext marker correction and regression test; five MCP test/fixture classes;
sanitized Keycloak realm descriptor; this report, architecture/roadmap and ADR notes plus
ADR-0009. The existing M13 files remain uncommitted alongside these changes. No commit,
push, merge, external PR, deployment or persistent application database operation occurred.

Known limits are intentional and documented:

- Newer 2026-07-28 MCP is not implemented by the selected compatible SDK. Clients must
  support a negotiated 2025 revision.
- Resource indicators in Keycloak 26.8 are experimental. Only the approved disposable
  instance was configured/tested. Existing local Keycloak needs a separately approved
  additive setup before ordinary interactive use; MCP remains disabled by default.
- Pre-registered PKCE clients and the official SDK are verified. DCR/CIMD, a browser
  Inspector frontend/CORS setup, VS Code's interactive UI and universal automatic OAuth
  discovery/registration/refresh are not claimed tested. The external-client example is
  configuration guidance, with the documented extension-host restriction.
- Authorization uses database snapshots and cannot retract in-flight data. Limits and
  rate admission are process-local. Uncooperative I/O retains tool capacity until exit;
  there is no forcible thread termination. Shared HTTP connector behavior remains the
  existing REST configuration; no separate MCP network-connection/slow-client policy is
  introduced in this local milestone.
- Read-only data can still contain hostile instructions or sensitive user-authored prose;
  external clients must treat it as untrusted. Backend-owned storage/configuration secrets
  are excluded. Citation syntax validation cannot prove semantic support.
- No physical purge, distributed enforcement, production exposure, write tools or agents.
  Organization listing returns at most 100 memberships with a truncation flag; paging that
  catalog further is a possible later read-only extension.

**Next checkpoint:** Review and accept M14 and its local compatibility limits. Do not
start M15 or alter the existing Keycloak instance without new authorization.
