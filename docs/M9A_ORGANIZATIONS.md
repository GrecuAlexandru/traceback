# M9A — Organizations API and bootstrap contract

Approved for local development on 2026-10-13. The separate [local Keycloak smoke test](LOCAL_KEYCLOAK_SMOKE.md) now verifies real human/service-account tokens; the verification below remains the original M9A record. Subsequent incident tenancy is documented in [M9B_INCIDENT_TENANCY.md](M9B_INCIDENT_TENANCY.md); the verification below records the original M9A task.

## API

Both endpoints require a bearer JWT validated by the existing resource server (signature, expiry, configured issuer and `traceback-api` audience), followed by the application policy below.

| Endpoint | Behavior | Response |
| --- | --- | --- |
| `POST /api/v1/organizations` | Accepts `{"name":"Platform"}`; creates an organization and its initial ADMIN membership atomically. Provision the user by `(issuer, subject)` if absent. | 201 with `{id, name, createdAt, role}`; `role` is ADMIN. |
| `GET /api/v1/organizations` | Lists only memberships matching the authenticated issuer and subject, newest first (UUID breaks ties). Does not create a user. | 200 with an array of `{id, name, createdAt, role}`; empty for a new identity. |

Name is required, nonblank, and at most 120 characters before surrounding whitespace is stripped. Malformed JSON or invalid names produce 400 ProblemDetail; a rejected bootstrap identity produces 403 ProblemDetail. Missing authentication produces 401 through Spring Security. No individual-organization GET, membership-management API, or pagination is added in M9A. Creation has no Location header because no individual-resource GET exists yet.

Only `name` is an input. Client fields such as user ID, organization ID, issuer, subject, and role have no authorization effect. Duplicate organization names are allowed; repeated creates intentionally create separate organizations. `role` comes from persisted membership data, with ADMIN, ENGINEER, and VIEWER represented by the schema and Java enum. ENGINEER/VIEWER assignment has no public endpoint yet.

## Human bootstrap policy

Require `traceback_principal_type` to be exactly the string `human` in the validated JWT, plus nonblank issuer and subject each within the existing 255-character column limits. Both organization endpoints fail closed before database access for tokens without that classification. No inference from email, preferred username, scopes, JWT administrator roles, or request fields is made. Machine identities have no organization bootstrap/listing policy in this milestone.

The identity provider is authoritative for this classification. For local Keycloak, use an administrator-controlled access-token mapper restricted to a dedicated human-login client; enable the standard authorization-code login flow with PKCE, disable service accounts and direct-access grants, and do not allow token exchange or a generic machine client to acquire this human classification. Do not derive the claim from a user-editable profile attribute or attach the human mapper to a shared/default client scope. A machine client must receive no human classification (or `service_account`). Misconfiguring the issuer to label a machine token as human defeats this trust boundary.

**Historical M9A evidence:** This original task did not change the live Keycloak realm or client configuration. Ordinary existing tokens without this claim receive 403 for organizations. Keycloak OIDC discovery is reachable and reports the expected issuer, but no real human or client-credentials token smoke test was performed. The new API tests simulate already validated JWTs and mock the decoder, so their evidence covers the application policy, not signature validation or mapper correctness.

## Persistence and compatibility

The pre-existing V3 already defines identity uniqueness, membership uniqueness, role constraints, foreign keys, and the membership user index. It and V1/V2 remain untouched. User insertion uses PostgreSQL `ON CONFLICT DO NOTHING` followed by a lookup in the same READ_COMMITTED transaction; concurrent requests share one identity while creating their own organizations. User, organization, and administrator membership writes all commit or roll back together.

No production database was migrated or reset during this task. Tests use disposable PostgreSQL containers, including a forced membership-insert failure and a V2-to-V3 upgrade containing an existing incident. M9A itself left incident tables, routes, services, and data unchanged. At M9A completion incidents were globally authenticated and not tenant-isolated. M9B now replaces those routes and quarantines legacy records; see its documentation. The later local Keycloak follow-up now supplies real-token evidence; keep the application local pending a separate deployment review.

## Verification

Commands ran from `backend/`, unless stated otherwise:

| Command | Result |
| --- | --- |
| `./mvnw clean test` (preflight, default Java 25 with release target 21) | PASS: original 11 tests; no failures/errors/skips. |
| `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Dtest=OrganizationApiIntegrationTest,OrganizationMigrationIntegrationTest test` | PASS: 16 new tests; no failures/errors/skips. |
| `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw clean test` | PASS: all 27 tests; no failures/errors/skips. |
| `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw verify` | PASS: all 27 tests; no failures/errors/skips; application JAR packaged. |
| `docker compose config --quiet` (root) | PASS: local configuration validates. |
| `docker compose ps` (root) | PASS: PostgreSQL healthy; Keycloak running; both bound to loopback. |
| `git diff --check` (root) | PASS after correcting whitespace on changed documentation lines. |

Self-review covered all changed and newly added implementation/tests, authorization queries, claim trust, SQL parameters, transaction boundaries, migration compatibility, secret handling, and scope. V1/V2/V3, incident implementation, Compose, JWT configuration, and dependencies have no diff. Local secret ignore patterns were checked for root/nested `.env` variants and private-key files. No local secret files, real JWTs, or production data were accessed; test data and principals were synthetic. No commits, pushes, PRs, migrations against the local persistent database, or external configuration changes were performed.

The initial targeted-test invocation from the repository root used the nonexistent `./mvnw` path and exited before running tests; the corrected command above ran from `backend/` and passed. Maven emitted non-fatal repository-metadata 401 warnings and existing Mockito agent warnings; both final Maven commands completed successfully.

## Next checkpoint

M9A is approved for local development, including its fail-closed issuer configuration contract. The later [local Keycloak integration](LOCAL_KEYCLOAK_SMOKE.md) completed real human and service-account checks, including a signed wrong-audience token. The original simulated JWT tests remain separate evidence. M9B incident tenant ownership and RBAC is the subsequent review checkpoint; its changes and evidence are documented separately.
