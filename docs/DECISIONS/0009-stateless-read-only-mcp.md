# ADR-0009 — Stateless read-only MCP with an independent OAuth resource

- **Status:** Accepted for the user-requested M14 local implementation; milestone review pending
- **Date:** 2026-10-14
- **Extends:** ADR-0001, ADR-0002, ADR-0005, ADR-0007, ADR-0008

## Decision

Use the Spring AI 2.0.1 WebMVC stateless Streamable HTTP transport and the official
MCP Java SDK 2.0.0 it depends on. Register exactly six read-only tools explicitly;
no annotation scanning, automatic Spring AI tool conversion, resources, prompts,
chat generation, or downstream tool federation. Import Spring AI's BOM. The JSON
mapper module is pinned to the transport's SDK version because the Spring AI BOM
does not manage that SDK artifact directly. Do not add a second protocol stack.

Keep MCP disabled by default. Enabling it requires a loopback listener and canonical
loopback resource URI. A dedicated earlier Spring Security chain validates Keycloak
JWT signatures, issuer, timestamps, required expiry and audience equal to that URI.
The URL is the MCP resource audience; `traceback-mcp` is the Keycloak resource-client
identifier, not an alternate audience accepted by Traceback. REST continues to use
its existing decoder and `traceback-api` audience. No decoder bean for MCP replaces
Boot's REST decoder. OAuth protected-resource metadata advertises configured values,
never values derived from Host/Forwarded headers. Keycloak remains the OAuth server.

Capture the existing authenticated Jwt in an immutable per-request SDK transport
context before thread changes. Pass that exact object explicitly into application
services. Neither sessions, thread-local propagation, tool arguments, headers naming
users nor model instructions establish identity. All tool operations recheck the
trusted-human policy and persisted memberships in existing services. Exact sources
use M12/M13's current-source resolver through a shared service, including every
ownership/publication/tombstone predicate. No new database schema or session store.

Bound HTTP bodies, tool arguments, output and concurrent work; use a process-wide
fixed-window request budget without per-identity authorization caches. Tool timeouts
interrupt a dedicated virtual thread but keep capacity occupied until the operation
actually exits. Late data is discarded. Disconnect/cancellation notifications do not
cancel unrelated requests or carry authority. No tool writes persistent application
state. Read annotations describe the tools; services and SQL enforce permissions.

## Alternatives and consequences

A stateful session has no useful benefit for this catalog and increases identity
confusion risk. Automatic tool discovery risks unintentionally exposing new writes.
A second microservice duplicates authorization and operational boundaries. Trusting
REST-only tokens broadens the resource boundary. A static audience mapper while
ignoring resource indicators obscures client/authorization-server incompatibility.

The selected SDK supports 2025-03-26, 2025-06-18 and 2025-11-25; it does not implement
the newer 2026-07-28 lifecycle/header requirements. It negotiates 2025-11-25 with a
compatible client; no newest-version compliance claim is made. Keycloak 26.8 resource
indicators are experimental and explicitly enabled only in the approved disposable
test instance. Existing local Keycloak remains unchanged. Pre-registered public PKCE
clients suffice; automatic DCR/CIMD interoperability is not claimed.

A small envelope guard adds bounded parsing and safe JSON-RPC errors before the SDK,
whose default unknown-method/parse handling can otherwise yield HTTP 500 or log input.
SDK server diagnostics are disabled; application metrics use fixed tool/outcome labels.
Untrusted prose remains nested data, separate from server-resolved source metadata.
External clients must still defend their own model prompts against poisoned content.

Authorization snapshots cannot retract a response already in flight. Limits and rate
budgets are per API process, deliberately local-only. Physical purge, distributed
limits, streaming, write tools, autonomous agents and deployment remain out of scope.

## Implementation and evidence

See [M14_SECURE_MCP.md](../M14_SECURE_MCP.md) for contracts, exact configuration,
real client/Keycloak results, regression commands and remaining limitations.
