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

**CURRENT:** JWT resource-server configuration and a protected incident API; no organization ownership yet.  
**PLANNED:** Application users, organizations, membership roles, scoped incident APIs, secure human login.
