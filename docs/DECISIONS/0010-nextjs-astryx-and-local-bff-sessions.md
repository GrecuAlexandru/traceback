# ADR-0010 — Next.js, Astryx and server-managed local browser sessions

- **Status:** Accepted for the requested local M16 implementation; milestone review pending
- **Date:** 2026-10-14
- **Extends:** ADR-0001, ADR-0002, ADR-0005, ADR-0008

## Context

M16 requests Next.js App Router, React 19+, TypeScript, Meta Astryx Neutral and real
Keycloak Code + PKCE. The REST audience and issuer-controlled human classification
must remain intact. Browser tokens and cross-organization cached data would create
additional exposure. The existing local public client has exactly one approved
callback, `http://127.0.0.1:8765/callback`.

## Decision

Use a separate `frontend/` Next.js application, pinned Astryx 0.7.0 and precompiled
component/theme CSS. Extend Neutral through `defineTheme` and compile that small
extension with the official CLI. Scoped application CSS implements feature layouts;
Astryx owns shell, navigation, tables, forms, menus, dialogs and status controls.
No competing component library, Tailwind or advanced StyleX compiler is introduced.

Use openid-client 6.8.8 on the server with public-client Code + S256 PKCE, random
state and nonce. Backend `/me` and `/organizations` validate the returned REST token
and trusted-human policy before the application creates a session. Reuse the approved
local client; change no Keycloak client, scope, mapper, redirect or credential.
Login always creates a fresh 256-bit opaque session ID. Tokens stay in a bounded
Node-process memory store; the browser receives only a host-only HttpOnly SameSite=Lax
cookie. HTTPS enables Secure and the __Host- cookie prefix. HTTP is permitted only
when explicitly enabled for loopback development. Idle/absolute bounds are 30 minutes/
eight hours; pending login state lasts five minutes. Refresh is single-flight; failure,
identity change and logout invalidate the session. Logout revokes the refresh token
when reachable and offers Keycloak's token-free SSO logout confirmation.

A same-origin BFF forwards only an explicit REST route/method allowlist to a fixed
server-configured origin. Writes require the configured Origin and a custom header.
Uploads/JSON, deadlines, redirects, errors and download headers are bounded. GETs
are cancellable; generation writes continue to observe durable backend state.
The backend remains the authorization authority on every request. No BFF role gate
replaces persisted membership, creator ownership or SQL predicates.

Use TanStack Query for bounded resource reads and mutation invalidation. A query
client belongs to one `(issuer, subject, organization)` boundary, is remounted on
switch and is cancelled/cleared on unmount. Queries include organization/resource
identity, use zero stale/unused-cache lifetime, and are never persisted. Only appearance
and a tab-local opaque chat replay UUID survive reload; replay UUIDs are cleared on
logout/switch. Questions, answers, excerpts and tokens are not browser-persisted.

## Alternatives and consequences

Browser-held refresh tokens increase exposure to JavaScript compromise. A persistent
session database or Redis is unnecessary for the current single-process local preview;
it becomes a separately reviewed packaging/deployment decision. A Next.js restart logs
users out, and multiple processes/serverless workers cannot share these sessions.
The preview is loopback-only; this ADR grants no production deployment approval.

Markdown is rendered without raw HTML or images, with restricted external links.
Citation dialogs resolve the current exact source before displaying its excerpt.
History is freshly checked on navigation/focus, pending polls and explicit refresh;
inaccessible citation resolution revalidates the parent history. Authorization changes
cannot retract previously rendered or in-flight snapshots instantaneously.

Chat is non-streaming. One UUID is reused for a saved submission; automatic write retries
are disabled. A missing replay handle and an apparently expired reservation require an
explicit new attempt, which deliberately creates a new user turn after backend recovery.
This preserves the M13 distinction between replay and regeneration.

## Implementation and evidence

See [M16_FRONTEND.md](../M16_FRONTEND.md) for actual contracts, commands, deterministic
versus real smoke evidence, visual findings, local setup and remaining limitations.
No Java source, migration, authentication policy, original source data or historical
model-evaluation artifact is changed by M16.
