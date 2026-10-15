# M16 — Full Next.js frontend with Meta Astryx

**Status:** Implemented and verified locally; user review pending. M9A–M15
are approved for local development by the M16 request. Only M16 is implemented on
`codex/m16-frontend`. No commit, push, PR, deployment or M17 work.

## Architecture and dependencies

`frontend/` is a separate Next.js App Router application. Server layouts protect the
workspace and route handlers own OIDC sessions and the BFF. Client feature components
own interactive API workflows; no browser calls a model or private storage service.
[ADR-0010](DECISIONS/0010-nextjs-astryx-and-local-bff-sessions.md) records the decisions.

Pinned runtime versions: Next.js **16.4.1**, React/React DOM **19.3.1**, Astryx core /
Neutral / CLI **0.7.0**, StyleX **0.19.1**, openid-client **6.8.8**, TanStack Query
**5.104.2**, react-markdown **10.1.0**, remark-gfm **4.0.1**. TypeScript is strict.
The lockfile pins the complete tree. Vitest, Testing Library, Playwright and axe-core
provide the smallest layered test set used here. ESLint uses typescript-eslint and
React Hooks rules; eslint-config-next was removed after its transitive development
pattern parser produced unpatched audit matches. The resulting tree has no known
npm audit matches at verification time. This is not a vulnerability-absence claim.

### Astryx integration and visual identity

The official `npx @astryxdesign/cli init` ran in `frontend/`, creating a separate agent
file. Root backend instructions were preserved. CLI component APIs and dashboard,
settings, table-filter, detail-page, login-sso and ai-chat templates were inspected.
Content-only Layout frames were adapted to actual backend capabilities; template
charts, bulk actions, tool calls, streaming and fixture content were omitted.

CSS imports follow the [official precompiled setup](https://github.com/facebook/astryx/blob/main/packages/core/README.md):
reset, core component CSS, Neutral CSS, then the compiled Traceback extension.
`src/theme/traceback.source.ts` extends the installed built Neutral theme; the CLI
produces committed CSS/JS/type metadata. No Babel/PostCSS/StyleX build plugin exists.
Theme customization uses restrained green accents, neutral surfaces, border tokens
and system typography. A system/light/dark preference is the only localStorage value.
No network font request or decorative raster asset is required.

Primary components: AppShell, SideNav/Heading/Section/Item, Layout/LayoutContent,
Table/Header/HeaderCell/Body/Row/Cell, Button, Selector, TextInput, TextArea, FileInput,
Dialog/DialogHeader, DropdownMenu, Banner, EmptyState, StatusDot, ChatMessage/Bubble,
Theme and LinkProvider. Citation buttons use a live resolver dialog; Astryx's URL-only
Citation component cannot itself perform that authorization check before displaying
an excerpt. Scoped CSS is limited to application composition, typography and safe
technical-content rendering. No competing UI library is installed.

Beta workarounds: a conditionally mounted dialog explicitly restores opener focus on
unmount; a border-only table frame cancels Astryx's inherited Layout container bleed;
the OAuth link uses `as="a"` to avoid Next.js RSC/prefetch requests to the issuer.
Theme output drift is checked with the official CLI. Neutral's inherited typography
metadata can mention Figtree; the app uses local sans-serif fallbacks without loading it.

## Screens and actual functionality

| Route under `/{organizationId}` | Behavior |
| --- | --- |
| `overview` | Five recent incidents, five stored documents, authorized API total counts and practical navigation; no fabricated metrics |
| `incidents` and `incidents/{id}` | Three real statuses, filtering, paging, details, create/edit/status/delete, expected-version writes and explicit conflict reload |
| `documents` and `documents/{id}` | Storage-state filter, multipart PDF/Markdown/TXT upload, optional pasted Markdown notes, title edit, authorized source/revision download, immutable revision history/new revision and tombstone confirmation |
| `search` | Deliberate keyword/semantic/hybrid submission, ranked bounded excerpts, section/page references, document navigation, empty/provider/degradation states |
| `assistant` and `assistant/{id}` | Private conversation history and paging, create/rename/delete, continued messages, durable pending/failure/replay states, safe Markdown, live citation resolution and redaction |
| `settings` | Organization/role, issuer/subject/account information, editable appearance and read-only application information |

The root routes a signed-in user to their newest listed membership. An identity with
no memberships can create an organization through the existing M9A endpoint. The shell
provides organization selection, account menu, theme control, breadcrumbs, a skip link
and Astryx's focus-trapped mobile navigation. ADMIN/ENGINEER management actions are
visible; VIEWER management actions are hidden, while personal conversations remain
available to every backend-supported role. The backend reauthorizes every operation.
There are no invitation, billing, member-administration or model-health controls.

Document storage AVAILABLE means stored original bytes; verification VERIFIED means
size/SHA-256 checked. Extraction/indexing states are read separately from M11/M12, including
NOT_SCHEDULED, PENDING_EXTRACTION, EXTRACTING, EXTRACTED, INDEXING, INDEXED, RETRY, FAILED
and CANCELLED. Latest-generation status does not invent an aggregate active-index count.
A failed replacement can coexist with an earlier valid publication. The list defaults
to AVAILABLE because that is the actual backend default; a deleted row can appear in
the explicit DELETED filter, but its detail/source endpoints are unavailable.

Uploads use indeterminate progress, measured byte/type checks and actionable errors.
Pasted notes become an actual UTF-8 `engineering-notes.md` File sent through the same
multipart validation/storage/ingestion API; they are bounded to 20,000 UTF-16 units
and the configured file byte cap. No preview executes uploaded HTML or Markdown.
Uploads have no backend idempotency key, so an ambiguous error asks the user to inspect
list/history before repeating it. Terminal ingestion retry/reindex remains operator-only.

## Authentication and security boundaries

The real local flow reuses `traceback-web-test` and exactly
`http://127.0.0.1:8765/callback`. Keycloak configuration, existing accounts, passwords,
client scopes and mappers were not modified. Its original read-only audit passed before
and after implementation. The REST `traceback-api` audience and issuer-controlled human
claim are preserved; the MCP audience is never substituted.

The [openid-client](https://github.com/panva/openid-client) server integration uses
Code + S256 PKCE, random state/nonce, a single-use five-minute login transaction and
fixed callback origin. It validates OIDC response identity and checks real backend
`/me` plus `/organizations` before rotating the opaque session. There is no client
secret. Access/refresh tokens stay in bounded process memory; none enter browser
storage, application JSON, logs or source links. Cookies are HttpOnly, SameSite=Lax,
path `/`, host-only, Secure/__Host-prefixed under HTTPS. Loopback HTTP is an explicit
local exception. Missing/expired/revoked/identity-changed sessions require sign-in.
Single-flight refresh prevents concurrent refresh-token rotation races. Each rotated
token is also checked through the backend identity endpoint, including refresh responses
that omit an ID token. Logout clears
client state, invalidates the local session, attempts refresh revocation and navigates
to token-free Keycloak SSO logout; Keycloak can require a human confirmation.

The BFF validates path/method allowlists, rejects redirects, uses a fixed backend
origin, and requires exact Origin plus a custom header on writes. JSON bodies are
capped at 128 KiB; multipart at file cap plus 1 MiB overhead. Regular requests have a
45-second deadline; chat submissions have 180 seconds to accommodate backend retrieval,
120-second generation and durable finalization. Only safe reads retry once on selected
gateway failures. Writes never automatically retry. GET cancellation propagates;
disconnects do not invent a cancellation contract for backend generation.

Responses are no-store, errors expose only fixed status/code messages, and downloads
are attachments with octet-stream/nosniff/sandbox headers through authorized REST.
Private S3 keys, paths and direct object URLs never enter the UI. A nonce-based CSP
limits scripts/connects/fonts to the application; raw HTML and images in model Markdown
are disabled, links allow only explicit HTTP(S) destinations with noopener/noreferrer.
Development allows script eval for Next tooling; inline styles remain allowed because
Astryx uses style attributes. No backend CORS or JWT check was weakened.

## Tenant state and chat durability

All query keys include organization and resource identity. A separate query client is
mounted for each issuer/subject/organization. Switch/logout/account expiry cancels and
clears prior data; zero unused-cache lifetime and no persisted query cache prevent
cross-tenant reuse. Memberships are refreshed on visibility and once per visible minute.
UI role information is advisory, never a second authorization source.

Chat submission generates one UUID. A tab-local sessionStorage entry keeps only that
UUID and scoped handle, allowing a reload to recover the question from durable history
and replay the same turn. It stores no message or excerpt and is cleared on logout or
organization switch. Pending writes/history poll every five seconds only while visible
and generating; terminal conversations do not poll continuously. No streaming is faked.

A first failed request can still have reserved messages. Check saved turn reuses its
key/text; it does not intentionally regenerate. An expired terminal key remains terminal.
If its handle is unavailable and a reservation has appeared stalled for over three
minutes, Start new attempt is explicit regeneration with a new UUID; the backend first
reconciles any expired reservation. Otherwise active-turn submissions remain blocked.
History paging is ascending as specified by the backend, with explicit refresh to the
latest page after submissions. The composer shows UTF-8 byte limits accurately.

Assistant Markdown is displayed only from authorized history. A citation dialog ignores
any cached excerpt and calls the exact conversation/message/reference resolver. A 403/404
resolver result hides the excerpt and refreshes the parent history. Focus/navigation,
explicit refresh and pending polls also revalidate history. Revocation cannot retract an
already viewed or in-flight authorized snapshot instantaneously; no such guarantee is made.

## Local setup

Prerequisites: Node 22+ (verification used Node 26.9.0), npm, the approved local
Keycloak client/account, and an approved backend/worker environment. No paid provider,
AWS account, new model or production deployment is needed.

```sh
cd frontend
npm ci
# Create only if absent; preserve any existing local configuration.
cp .env.example .env.local
npm run dev
```

Server-only examples in `.env.example` set APP_ORIGIN to `http://127.0.0.1:8765`,
OIDC_ISSUER to `http://localhost:8180/realms/traceback`, OIDC_CLIENT_ID to
`traceback-web-test`, BACKEND_ORIGIN to the approved local API, ALLOW_LOCAL_HTTP=true,
DOCUMENT_MAX_FILE_BYTES=10485760 and CHAT_MAX_QUESTION_BYTES=1000. There are no public
secret variables. Keep frontend limits aligned with any explicitly changed backend limits.
A different frontend origin/callback requires separately approved Keycloak changes.

Do not start a backend against an unreviewed persistent database just to test this UI.
The smoke run used a separately named tmpfs PostgreSQL container:

```sh
# New unused name, ephemeral storage, loopback-only development trust authentication.
docker run --detach --name traceback-m16-smoke-pg \
  --label dev.traceback.purpose=m16-browser-smoke --publish 127.0.0.1::5432 \
  --env POSTGRES_DB=traceback_m16 --env POSTGRES_USER=traceback_m16 \
  --env POSTGRES_HOST_AUTH_METHOD=trust --tmpfs /var/lib/postgresql/data \
  pgvector/pgvector:pg17

docker port traceback-m16-smoke-pg 5432
```

The actual host port was 53040. API/worker ran the existing M15 JAR with Java 21 on
8766/8767, datasource `jdbc:postgresql://127.0.0.1:53040/traceback_m16`, user
`traceback_m16`, empty test password and private storage
`/private/tmp/traceback-m16-private-documents`. `/tmp` itself is a macOS symlink and
was correctly rejected by the storage adapter; the canonical private path fixed startup.
V1–V8 ran only on this disposable database.

For real ingestion, the existing local emulator received a separate synthetic bucket
`traceback-m16-smoke-documents` and queue `traceback-m16-smoke-ingestion`; existing
application resources were not reset. API/worker selected those names via local settings,
INDEXING_ENABLED=true and the already approved nomic digest. API additionally enabled
the already installed Qwen 7B digest. Existing approved model/resource caps were retained.
The frontend's actual BACKEND_ORIGIN was `http://127.0.0.1:8766`.

## Verification record

Final frontend verification completed on 2026-10-15. Run frontend commands from
`frontend/`; run infrastructure commands from the repository root. Deterministic
unit/browser checks are separate from the real integration evidence below.

| Exact command | Result |
| --- | --- |
| `npm ci` | Passed; clean dependency installation |
| `npm run typecheck` | Passed; strict TypeScript |
| `npm run lint` | Passed |
| `npm run test` | Passed: 27 tests across four files; none skipped |
| `npm run build` | Passed; production App Router build, no material warnings |
| `npm run test:e2e` | Passed: 14 Chromium tests; none skipped (26.1 seconds) |
| `npm run test:e2e -- --grep 'major screens'` | Passed after strengthening the mobile drawer animation assertion (one test, 4.4 seconds) |
| `npm audit` | Passed: zero reported vulnerabilities |
| `npm run astryx -- theme build src/theme/traceback.source.ts --out src/theme/traceback.css --check` | Passed: committed theme output matches source |
| `git diff --check` | Passed |
| `docker compose config --quiet` | Passed |
| `docker compose ps` | Passed; services were running during real smoke; final recheck showed no running services |
| `python3 scripts/keycloak_local_setup.py` | Read-only audit passed before and after real smoke; final recheck could not run because Keycloak was stopped |

The final environment recheck found all Docker containers stopped, including the
disposable tmpfs database, Keycloak and model services. They were not restarted or
reconfigured. Completed real-smoke screenshots/results below describe the earlier
running environment; they do not claim that those services remain available now.
Recreate disposable smoke data for another real run. The owned API/worker smoke
processes were stopped after detecting unavailable dependencies.

No Java source or configuration changed, so backend `./mvnw clean test` and
`./mvnw verify` were not rerun for M16; prior M15 evidence is historical. The existing
JAR was exercised against real JWTs and disposable data. No tests were skipped in
the frontend suites. Runner-only NO_COLOR/FORCE_COLOR environment warnings do not
appear in the application browser console.

## Real browser/backend smoke results

The Codex in-app browser used actual Keycloak and the unchanged JWT-validating backend;
no mocked decoder or fixture API was in this path. The human entered the existing test
account password privately. An initial five-minute login transaction expired while
waiting; a fresh PKCE flow reused the established SSO login and succeeded.

- Real sign-in, identity validation, trusted-human membership bootstrap and restoration.
- Organization creation/listing and overview loaded from disposable PostgreSQL.
- Incident creation/details and expected-version update from version 0 to 1.
- Markdown notes upload through the real multipart API, private storage and scheduled
  verification/indexing worker: AVAILABLE, VERIFIED and INDEXED, three published chunks.
- Real hybrid search using installed nomic embeddings returned authorized passages.
- A real private conversation and one Qwen turn completed ANSWERED in **31.0 seconds**,
  with one user message, one assistant message and one cited evidence row. The answer
  correctly stated pool size 20 and closing leaked connections; this single synthetic
  example is an integration smoke, not a model-quality benchmark.
- Full-page reload restored the persisted answer. S1 resolved through the actual live
  citation endpoint and linked to the authorized source document.
- A real new immutable source revision retained revision 1 in history and caused the
  old assistant answer and citation buttons to become REDACTED on fresh history read.

The in-app browser file-chooser automation timed out; native Codex app controls were
unavailable. Standard file selection/upload/download/revision flows passed the separate
Chromium suite. The real upload used the same multipart API via pasted Markdown notes.
Real browser PDF upload, deletion, multiple-human tenant isolation, and all provider
failure modes were not re-executed; deterministic UI tests and existing backend suites
provide their separately stated coverage. No password/token was saved in screenshots.

## Visual and accessibility review

Desktop 1440×1000 and mobile 390×844 Chromium screenshots cover login, overview,
incident list/detail, document list/detail, search, chat, settings, both themes and
mobile navigation. The real in-app browser additionally verified a 755-pixel viewport.
The generated primary design concept was compared with the rendered UI via view_image.
Its neutral surfaces, green accent, left rail, typography hierarchy and table composition
are implemented. Actual UUIDs/integer versions replace illustrative incident identifiers;
unsupported incident text search/sorting and concept-only component examples are omitted.
No mock data appears in functional screens; deterministic screenshots use test fixtures.

Review fixes: missing Layout gutters, global link color overriding Astryx navigation,
cramped chat actions, back-link width, missing dialog focus restoration and clipped table
headings caused by inherited container bleed. Mobile drawer screenshots now wait until
its links fully enter the viewport. Automated contrast checks wait for enabled controls and
both the theme attribute and resolved color to settle after transitions. Screenshot and
console checks are distinct from compilation; CSP/OAuth fetch warnings were fixed.

Axe checks WCAG 2 A/AA and 2.1 AA on six loaded screens and dark settings. Keyboard tests
cover dialog opening/Escape/focus return and responsive navigation. Reduced motion is
honored. This is not a manual screen-reader audit, every possible Markdown/content
contrast check, browser matrix or full WCAG conformance certification.

Screenshot evidence is retained outside the checkout at `/tmp/traceback-m16-qa`,
including `live-assistant.jpg`, `live-citation.jpg` and `live-redaction.jpg` from the real
run. Production fixture screenshots are clearly distinguished from those real captures.

## Files, data review and remaining limitations

Changed: new frontend application, typed contracts/API writes, OIDC/BFF/session boundary,
feature components/styles/compiled theme, tests/fixtures/configuration/lockfile/examples,
root and frontend agent guidance, `.gitignore`, architecture/roadmap, ADR-0010 and relevant
ADR implementation notes. No backend source, Compose configuration, historical migration,
Keycloak configuration or M12–M15 evaluation data was changed.

The review checks fixed server origins/routes, CSRF, cookie rotation, refresh/logout
races, stream/body/deadline limits, no token-bearing JSON/URLs/logs, live backend role/
creator/tenant authorization, version writes, untrusted content and fresh citation checks.
Synthetic temporary application data is separate from persistent user data. The local
preview remains loopback-only, with no commits or external effects beyond approved local
identity/model services and isolated emulator test resources.

Remaining limits: sessions are one-process memory and expire on restart; multi-instance
or serverless deployment needs a reviewed store. Keycloak logout may ask for confirmation
and does not revoke offline-validated access JWTs instantaneously. GET does not reconcile
crashed backend chat reservations; explicit replay/new-attempt semantics are preserved.
No SSE, preview renderer, invitation API, indexing retry API, model-health API, global
analytics, restore/purge or production packaging is invented. Latest-page/history
navigation is explicit. Testing covers Chromium plus the local in-app browser, not a
full Firefox/Safari/device or screen-reader matrix. No Lighthouse performance claim is
made; the production build is the recorded performance gate, and reads remain bounded.

**Next checkpoint:** User review and acceptance of M16. Then propose M17 packaging and
release requirements, including durable sessions and the existing deployment/security
gaps. M17 and AWS provisioning/deployment remain unstarted and require separate approval.
