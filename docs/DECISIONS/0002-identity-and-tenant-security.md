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

**PLANNED:** Authorization for future RAG/MCP modules and separately approved deployment. See [M9B incident tenancy](../M9B_INCIDENT_TENANCY.md).


**CURRENT M10:** Document/revision metadata, counts, downloads and writes reuse the human/membership policy. Read SQL includes tenant and identity predicates; write SQL also checks ADMIN/ENGINEER. Composite foreign keys prevent foreign document/revision pointers. Downloads recheck persisted access immediately before bounded file reads. Tombstones hide current and historical content. JWT and live Keycloak configuration are unchanged. See [M10](../M10_DOCUMENT_MANAGEMENT.md).

**CURRENT M11:** Preparation status reads reuse human/membership/document/revision checks. Workers validate all message references against database-owned events and composite tenant metadata before content access; payload roles/organization IDs are never authority. Tombstones and supersession fence completion. Worker HTTP denies application routes; no public retry or download bypass. Local emulator ACL/public-block checks do not establish production IAM enforcement.
