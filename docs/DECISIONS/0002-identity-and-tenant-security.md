# ADR-0002 — OIDC identity with application-owned tenant authorization

- **Status:** Accepted
- **Date:** 2026-10-13

## Context

JWT validity alone does not establish which organization a caller can access. Technical documents and incident histories may contain private operational information.

## Decision

Use a standard OIDC identity provider (Keycloak locally) and Spring Security OAuth2 resource-server validation for issuer, signature, expiry, and API audience. Represent application users by `(issuer, subject)`. Store organizations, memberships, and `ADMIN`/`ENGINEER`/`VIEWER` roles in PostgreSQL. Enforce membership and resource ownership on every service and query path, including RAG and MCP.

## Alternatives considered

- **Custom password-based authentication:** security burden without learning benefit.
- **Trust tenant ID or roles supplied by the browser:** unsafe and easy to spoof.
- **Rely solely on identity-provider groups/roles:** complicates app-specific membership lifecycle and data consistency.

## Consequences

- Explicit security tests for known foreign resource IDs and cross-tenant search are mandatory.
- Service-account identities must not automatically become human tenant administrators.
- Existing tenantless incidents need a safe migration/quarantine plan; no cross-tenant default visibility.

## Implementation status

**CURRENT:** JWT resource-server configuration, M9A application users/organizations/membership roles, and M9B organization-scoped incident CRUD/RBAC. Service methods authorize current memberships; incident read/count and SQL mutation queries also enforce identity/tenant/role predicates. Global incident routes are denied. Legacy incidents remain unassigned and inaccessible under V4. Organization creation/listing and incident service entry points require a trusted issuer-assigned `traceback_principal_type=human` claim; generic/service-account tokens lacking that explicit classification are denied. The issuer must restrict the mapper to a human-login client with service accounts disabled and prevent user-controlled claims. Organization creation atomically provisions the identity and initial ADMIN membership; listing filters by issuer and subject. See [M9A API and bootstrap contract](../M9A_ORGANIZATIONS.md).

**CURRENT local integration:** `traceback-web-test` is a public Code + required S256 PKCE client with an exact loopback callback and dedicated access-token human/audience mappers. Machine/exchange grants are disabled. `traceback-cli` remains unchanged and lacks the human claim. Real human bootstrap/incident access, CLI 403 denial, invalid-signature/missing-token 401, and wrong-audience 401 checks passed against a disposable backend/database. See [local verification](../LOCAL_KEYCLOAK_SMOKE.md).

**Historical M9 plan:** Extend authorization to RAG/MCP (now implemented through M14 below). Deployment still requires separate approval. See [M9B incident tenancy](../M9B_INCIDENT_TENANCY.md).


**CURRENT M10:** Document/revision metadata, counts, downloads and writes reuse the human/membership policy. Read SQL includes tenant and identity predicates; write SQL also checks ADMIN/ENGINEER. Composite foreign keys prevent foreign document/revision pointers. Downloads recheck persisted access immediately before bounded file reads. Tombstones hide current and historical content. JWT and live Keycloak configuration are unchanged. See [M10](../M10_DOCUMENT_MANAGEMENT.md).

**CURRENT M11:** Preparation status reads reuse human/membership/document/revision checks. Workers validate all message references against database-owned events and composite tenant metadata before content access; payload roles/organization IDs are never authority. Tombstones and supersession fence completion. Worker HTTP denies application routes; no public retry or download bypass. Local emulator ACL/public-block checks do not establish production IAM enforcement.


**CURRENT M12:** Search/status reuse trusted-human identity and current persisted membership. Keyword/vector SQL independently filters organization, issuer/subject membership, active/current revision and published INDEXED generation before ranking/limits. Query embedding follows authorization, with reauthorization after provider I/O. Known foreign IDs, corpus contamination, revocation and tombstones are tested. JWT configuration is unchanged. See [M12](../M12_EXTRACTION_AND_RETRIEVAL.md).


**CURRENT M13:** Private persistent conversations, durable/idempotent ordered turns, bounded local inference and assigned-source citation validation are implemented in `rag` with append-only V8. All supplied and inherited source dependencies are checked against current authorization/publication before generation, finalization and historical reads; invalid historical answers are redacted. No tools or MCP. See [ADR-0008](0008-private-conversations-and-validated-rag-evidence.md) and [M13](../M13_RAG_CHAT.md).


**CURRENT M14:** Disabled-by-default, loopback-only stateless MCP exposes six explicit read-only
tools through existing authorized services. A separate JWT chain requires the canonical MCP
resource audience while REST retains traceback-api. Original validated JWTs cross threads in
an immutable request context; no session confers identity. Exact source resolution retains all
M12/M13 live visibility predicates. Protected-resource metadata, strict limits, fixed-label
metrics and real isolated Keycloak/official-client tests are provided. M13 citation markers
reject unsupported dialects while preserving normal Markdown. Earlier implementation notes
above describe their historical checkpoints. See [ADR-0009](0009-stateless-read-only-mcp.md)
and [M14](../M14_SECURE_MCP.md) for version/OAuth limitations and actual verification.
