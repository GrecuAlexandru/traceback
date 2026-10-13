# M9B — Incident tenancy and RBAC

Approved for local development on 2026-10-13. The separate [local Keycloak follow-up](LOCAL_KEYCLOAK_SMOKE.md) now supplies real-token verification; the M9B test evidence below remains historical. M9A was approved for local development. No Milestone 10 work is included.

## Migration strategy and legacy quarantine

The strategy was stated before implementation: preserve existing incidents in place without guessing their owners, exclude them from all public queries, and enforce ownership for new writes.

Append-only `V4__scope_incidents_to_organizations.sql` adds:

- Nullable `organization_id UUID`, initially NULL for every existing incident; no records are deleted, copied, or assigned.
- A foreign key to organizations with `ON DELETE RESTRICT`, preventing incident loss through organization deletion.
- `CHECK (organization_id IS NOT NULL) NOT VALID`. PostgreSQL permits the pre-existing NULL rows to remain while enforcing the check on every new INSERT and UPDATE. An ordinary edit of a legacy row fails unless it also supplies explicit ownership.
- Tenant/date/UUID and tenant/status/date/UUID indexes for lists, pagination, and status filtering.

The organization column intentionally remains nullable for historical rows. Every application query requires a concrete authorized organization; NULL ownership matches no organization. There is no public reassignment, quarantine list, or legacy download endpoint. The legacy rows retain their UUIDs, content, timestamps, statuses, and versions.

Later assignment or archival requires a separate reviewed operation with evidence of the rightful owner, row counts, and post-operation verification. Do not infer ownership from the first organization or administrator. Only after every remaining incident has explicit ownership may a new migration validate the check and make the column NOT NULL. No such operation occurs in M9B.

This is a cutover migration: stop older backend instances before applying V4 and starting the M9B backend. Older code still has global reads and its unowned INSERTs are incompatible with the new check; mixed-version rollout is unsafe. Test migrations ran only on disposable PostgreSQL containers. The local persistent database was not modified. No public exposure or deployment is authorized by these tests.

## API contract

The collection is `/api/v1/organizations/{organizationId}/incidents`; the item is that route plus `/{id}`.

| Method and route | Contract |
| --- | --- |
| POST collection | Same title/description validation as before. Server assigns ownership from the authorized route, status OPEN, and version 0. Returns 201 with an item Location. |
| GET collection | `page` defaults to 0, `size` to 20 (maximum 100), optional `status`. Both items and pagination counts are scoped to the authorized organization. Sorted by creation time DESC, UUID DESC. |
| GET item | Returns only an authorized incident in the selected organization. |
| PATCH item | Requires the expected nonnegative `version`; retains optional title/description/status fields and their validation. A real change atomically increments version. No-op patches preserve version, as the former JPA dirty-checking behavior did. |
| DELETE item | Requires `?version=n`, a nonnegative expected version. Returns 204 on success. A stale version returns 409 without deletion. |

Incident response DTOs include `organizationId` alongside the existing id/title/description/status/version/createdAt fields. Client ownership, user identity, and role fields cannot override the route or membership policy. Request DTOs do not accept these fields as authority.

The global `/api/v1/incidents` and all descendant paths have no controller and are explicitly denied by Spring Security. Authenticated calls return 403; unauthenticated calls return 401. No redirect, global list, or compatibility mutation remains. This is an intentional breaking change to the old API.

## Authorization and error behavior

The existing resource server remains responsible for JWT signature, expiry, issuer, and audience validation. The M9A trusted `traceback_principal_type=human` policy applies to incident service entry points too. Identity is `(issuer, subject)`; email, username, scopes, JWT role claims, and request fields are not membership authority.

| Persisted membership in the selected organization | Read item/list/filter | Create/PATCH/DELETE |
| --- | --- | --- |
| ADMIN | Allowed | Allowed |
| ENGINEER | Allowed | Allowed |
| VIEWER | Allowed | 403 |
| No membership (including a nonexistent organization) | 403 | 403 |

Every service method checks current database membership before reading incident state. A user's roles can differ between organizations, and downgrades/removals take effect without reissuing the JWT. Valid unauthorized requests return 403 regardless of the incident UUID; a nonmember never gets an existence or version check.

For callers authorized for an operation in the selected organization, unknown, foreign-organization, and legacy incident UUIDs produce the same 404 ProblemDetail. Expected versions are checked only after finding an authorized incident. Stale versions of owned incidents produce 409 with the existing incident conflict response. Malformed requests/UUIDs, invalid pagination/status, and missing or negative versions produce 400; authentication failure produces 401.

The incident repository exposes no unscoped `findAll`, `findById`, `save`, or `delete` methods. Read and count queries include both organization ownership and an identity-based membership EXISTS predicate. SQL INSERT/UPDATE/DELETE also include an ADMIN/ENGINEER membership predicate. UPDATE and DELETE additionally match organization ID, incident UUID, and expected version in one statement. These are application SQL boundaries, not PostgreSQL RLS or protection from privileged administrative SQL.

The entity retains `@Version`; explicit SQL updates implement compare-and-swap and increment the version atomically. Write transactions explicitly use READ_COMMITTED. An update/delete that loses a version race never silently retries with a newer version. If membership has been revoked or downgraded before a failed mutation's recheck, 403 takes precedence over 409. If an incident is deleted before its initial lookup, the later request gets 404; a race lost after an authorized lookup gets 409. PostgreSQL statement snapshots define concurrent authorization checks; writes check membership in the mutation itself. A failed create or post-write response lookup rolls back the incident transaction.

## Test coverage and limits

Testcontainers PostgreSQL and distinct simulated users cover:

- Valid creation, item reads, partial updates, deletion, response ownership/Location, no-op PATCH, identity responses, validation, pagination, and status filtering.
- ADMIN/ENGINEER writes and VIEWER reads; denied VIEWER writes, absent membership, nonexistent organization, removed/downgraded membership, different roles per organization, issuer/subject identity, and machine-principal denial.
- Known foreign UUIDs and legacy IDs on GET/PATCH/DELETE; foreign organization routes on every operation; spoofed request identity/ownership/role; isolated list/filter items and total counts.
- Direct service denials and independent repository identity/role/tenant predicates, including mutations returning zero for a denied caller.
- Stale PATCH/DELETE; simultaneous PATCH/PATCH and PATCH/DELETE with one winner; rollback of incident and membership changes after a post-mutation authorization failure.
- Real V3-to-V4 upgrade with an existing incident, preserved fields/version, idempotent migration rerun, new NULL/foreign ownership rejection, legacy-edit rejection, owner-null rejection, and restricted organization deletion.
- Legacy fixture inserted before V4, kept intact and invisible across organizations; every retired global route/method denied.
- All 9A organization tests retained. The historical V2-to-V3 test is pinned to V3; the new V3-to-V4 test covers 9B separately.

**Historical M9B test limitation:** At M9B completion, live Keycloak testing remained pending. Tests inject simulated authenticated JWTs and mock the decoder; they do not prove cryptographic validation, issuer mapper correctness, or real human versus client-credentials classification. No realm/client mapper was installed or changed. Real human/service-account token smoke testing was therefore still needed at that checkpoint; the follow-up now provides it with the issuer-controlled configuration described in [M9A_ORGANIZATIONS.md](M9A_ORGANIZATIONS.md). Tokens without the trusted human claim fail closed. The later [local Keycloak follow-up](LOCAL_KEYCLOAK_SMOKE.md) now verifies the dedicated client, real human/CLI tokens, and wrong-audience rejection. Keep the application local pending a separate deployment review.

## Verification and review

Commands actually executed (all passed; no test failures, errors, or skips):

| Command | Location | Result |
| --- | --- | --- |
| `JAVA_HOME=$(/usr/libexec/java_home -v 21) backend/mvnw -f backend/pom.xml -Dtest=IncidentApiIntegrationTest,IncidentOwnershipMigrationIntegrationTest,OrganizationApiIntegrationTest,OrganizationMigrationIntegrationTest test` | Root | Initial targeted pass: 42 tests, before the additional no-op/rollback/concurrent-delete cases. |
| `JAVA_HOME=$(/usr/libexec/java_home -v 21) backend/mvnw -f backend/pom.xml -Dtest=IncidentApiIntegrationTest test` | Root | 28 incident tests passed after those cases were added. |
| `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw clean test` | `backend/` | Final complete suite: 45 tests passed. |
| `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw verify` | `backend/` | Final complete suite: 45 tests passed; application JAR packaged. |
| `docker compose config --quiet` | Root | Configuration valid. |
| `docker compose ps` | Root | PostgreSQL healthy, Keycloak running; both bound to loopback. |
| `git diff --check` | Root | Passed. |

Final suite: 28 incident API/service/repository tests, 15 organization API tests, one historical V2-to-V3 migration test, and one V3-to-V4 ownership migration test. Testcontainers used disposable PostgreSQL 17 containers. No persistent database migration or data reassignment was performed. Maven reported existing non-fatal repository-metadata 401 and Mockito agent warnings; both required Maven checks completed successfully.

Self-review compared the 9B changes against a snapshot of the approved uncommitted 9A implementation, then reviewed every affected runtime path and test. The reviewed security properties are:

- No global incident route/controller or unscoped incident repository method remains. Every lookup/list/count is ownership- and identity-filtered; every SQL mutation additionally enforces ADMIN/ENGINEER membership.
- Organization ownership is taken from an authorized route and is never changed by PATCH; user identity comes from the validated JWT, and permission comes from PostgreSQL. SQL uses bound parameters and server-selected sort fields.
- Unauthorized callers reach no incident existence/version check. Missing/foreign/legacy 404 responses carry the same problem fields except their request instance URI. Version conflicts are reported only for authorized incidents.
- PATCH/DELETE use atomic expected-version predicates. Version races cannot overwrite/delete a newer revision; failed transactions roll back writes. Authorization is checked at the SQL statement snapshot; these checks are not a global database RLS policy.
- V1–V3 are byte-for-byte unchanged. V4 performs no ownership assignment or data deletion and prevents new NULL ownership and invalid foreign keys. Legacy rows remain quarantined; final NOT NULL validation is intentionally deferred.
- Approved 9A user/organization implementation and secret-ignore rules were preserved, with only the new membership-role query and necessary test-fixture/documentation updates. Dependencies, datasource/JWT issuer/audience configuration, and Compose are unchanged. No secret files, real tokens, or production data were accessed; new tests use synthetic fixtures and principals.

At M9B completion, the known limitations included pending live identity-provider verification. The local Keycloak follow-up now resolves that check. The need to stop old global-route instances before cutover and the separately reviewed legacy assignment/archive operation remain. The original M9B suites make no real-token verification claim; the separate follow-up supplies that local evidence. No production-readiness claim is made.

## Next checkpoint

M9B has been approved locally, including the legacy quarantine/cutover and new version-aware DELETE contract. After approval, the next planned milestone is M10 (knowledge document metadata); it has not been started. No commits, pushes, merges, PRs, deployments, or live Keycloak changes were made.
