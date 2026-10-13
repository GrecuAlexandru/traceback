# Local Keycloak integration and authentication smoke test

Verified on 2026-10-13 after the user approved M9A/M9B for local development and explicitly approved the additions below. No Milestone 10 work, deployment, commits, pushes, or merges occurred.

## Preserved local setup

Existing Compose runs Keycloak 26.8.0 at `http://localhost:8180`, published on `127.0.0.1`, and persistent PostgreSQL on loopback port 5433. The `traceback` realm already existed. Registration was disabled. The existing confidential `traceback-cli` client had service accounts enabled and a dedicated `traceback-api` audience mapper; its direct, default, and optional scopes contained no human classification mapper. Legacy token exchange (`TOKEN_EXCHANGE`) was already disabled in the server profile.

Backend issuer remains `http://localhost:8180/realms/traceback`; expected audience remains `traceback-api`. No backend source/configuration, Compose settings, shared client scopes, existing clients/users, or V1–V4 migration contents changed in this follow-up.

## Approved additive configuration

The sanitized manifest is [local-smoke-config.json](../dev/keycloak/local-smoke-config.json). It is an additive client/user descriptor, not a realm export or destructive import.

| Setting | `traceback-web-test` | `traceback-web-test-wrong-audience` |
| --- | --- | --- |
| Client authentication | Disabled (public client) | Disabled |
| Authorization Code Flow | Enabled | Enabled |
| PKCE | Required S256 | Required S256 |
| Redirect URI | Exactly `http://127.0.0.1:8765/callback` | Same exact URI |
| Implicit / Direct Access / service-account grants | Disabled | Disabled |
| Standard token exchange / device / CIBA / JWT authorization grants | Disabled | Disabled |
| Default / optional scopes | `basic` only / none | `basic` only / none |
| Web origins | Empty | Empty |
| Dedicated hardcoded access-token claim | `traceback_principal_type=human`, JSON type String | Same |
| Dedicated access-token audience | `traceback-api` | `traceback-not-api` |

The diagnostic client permits a real signed, issuer-matched human token with the wrong API audience without modifying existing client mappers. Neither human mapper is attached to a shared scope. The helper confirms the relevant settings and rejects unexpected mappers, grant settings, redirects, or shared human classification. Keycloak adds mapper defaults such as introspection flags; the audit compares all requested values while tolerating unspecified server defaults. See the [Keycloak administration guide](https://www.keycloak.org/docs/26.7.0/server_admin/index.html) for client flow and PKCE settings, and [token exchange documentation](https://www.keycloak.org/securing-apps/token-exchange) for exchange controls.

One synthetic, enabled user, `traceback-smoke-user`, was created without credentials, email, names, or other real profile information. The user privately set its password in the local admin console and completed the real browser login. Scripts never requested, displayed, or stored that human password. Existing user/client settings were not replaced. The new test clients/user remain available for local reuse.

## Reusable standard-library tools

Run from the repository root:

```bash
# Read-only audit; prints only sanitized findings and the synthetic user's setup link.
python3 scripts/keycloak_local_setup.py

# Only after explicit approval of the manifest: add absent clients/user.
# Existing objects are verified, never overwritten or deleted.
python3 scripts/keycloak_local_setup.py --apply

# Once the test user's password is privately set and the local backend is healthy:
python3 scripts/keycloak_pkce_smoke.py --api-base http://127.0.0.1:8766
```

The setup helper authenticates to the local admin API using the existing bootstrap admin values from the running Compose container, transiently in memory. It does not print Docker inspection output, write an admin credential file, or reset credentials. If those bootstrap credentials have been rotated, it stops safely; resolve access interactively rather than resetting the realm. Human password setup remains intentionally interactive.

The smoke script uses only Python standard-library modules. It generates a random verifier, S256 challenge, state, and nonce; opens the default browser; receives only the exact loopback callback; verifies state/issuer and nonce; and exchanges the authorization code without a public-client secret. The wrong-audience flow reuses the browser SSO session with `prompt=none`. If it requires login again, the run stops safely and can be retried after interactive login.

The scripts keep access/ID/refresh tokens, authorization codes, and the existing CLI client secret in memory and never print or write that material to a file. Refresh/ID token references are discarded after the exchange checks. Callback access logs are suppressed. HTTP requests bypass environment proxies, permit only HTTP loopback URLs, enforce timeouts/response size limits, and refuse API/token redirects. Separate PKCE probes inspect error redirects without following them or displaying their raw URLs. The CLI service secret is fetched transiently from the local admin API; no secret argument, environment export, or credentials file is needed.

JWT payload decoding in Python is not signature verification. Successful real-token `/api/v1/me` calls exercise Spring's actual JWT decoder, configured issuer/audience checks, and Keycloak's JWKS. No mocked decoder or simulated JWT is used in this smoke run. The separate unit/Java integration suites retain their original simulated-fixture scope.

## Disposable backend/database recipe

The verified run used a separate PostgreSQL 17 Alpine container with tmpfs storage, synthetic data, and an ephemeral loopback host port. Its test-only local trust authentication avoids creating or persisting a database password. Existing Compose PostgreSQL and its data were untouched.

```bash
# Choose a new unused container name; never remove/reuse an unrelated existing one.
docker run --detach --name traceback-oidc-smoke-pg \
  --label dev.traceback.purpose=oidc-smoke-test \
  --publish 127.0.0.1::5432 \
  --env POSTGRES_DB=traceback_smoke \
  --env POSTGRES_USER=traceback_smoke \
  --env POSTGRES_HOST_AUTH_METHOD=trust \
  --tmpfs /var/lib/postgresql/data postgres:17-alpine

docker port traceback-oidc-smoke-pg 5432

# Build from backend/ with Java 21, then return to the root:
# JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw verify
# Substitute the host port printed above for PORT in this JDBC URL.
/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home/bin/java \
  -jar backend/target/traceback-backend-0.0.1-SNAPSHOT.jar \
  --server.address=127.0.0.1 --server.port=8766 \
  --spring.datasource.url=jdbc:postgresql://127.0.0.1:PORT/traceback_smoke \
  --spring.datasource.username=traceback_smoke --spring.datasource.password=

# From a second terminal, run the smoke script above and complete browser login.
# Afterward stop ONLY this test Java process (Ctrl+C in its own terminal), then:
docker stop traceback-oidc-smoke-pg
docker rm traceback-oidc-smoke-pg
```

This trust/tmpfs setup is for disposable synthetic data on the developer machine only. No volumes are deleted by this recipe. Do not run it against the persistent application database. If verifying that database later, stop old global-route backend instances before V4, as documented in M9B.

## Actual verified evidence

The machine-readable sanitized record is [smoke-results.json](../dev/keycloak/smoke-results.json); it contains no tokens, passwords, secrets, subjects, profile details, or generated resource UUIDs.

| Real request | Actual HTTP status |
| --- | --- |
| Human GET `/api/v1/me` | 200 |
| Human POST / GET organizations | 201 / 200 |
| Human POST / GET own incident | 201 / 200 |
| Service account GET `/api/v1/me` | 200 (authentication succeeds) |
| Service account POST / GET organizations | 403 / 403 |
| Service account list / GET / POST / PATCH / DELETE incidents | 403 for all five |
| Missing token / malformed token / tampered JWT signature | 401 for all three |
| Wrong-audience token: identity / organizations / incidents | 401 for all three |
| Human DELETE test incident using its expected version | 204 |
| Missing challenge / plain PKCE authorization requests | 302 to the exact callback with `error=invalid_request`, matching state, and no authorization code |

All 20 API status checks passed. Human tokens matched the configured issuer, audience, authorized client, nonempty subject, unexpired lifetime, and exact String human classification. The real service token matched issuer/API audience/client and had no principal classification claim. The wrong-audience human token matched issuer/client/classification, included `traceback-not-api`, and excluded `traceback-api`. Only boolean results are retained.

The run created one application user, organization, ADMIN membership, and incident in the disposable database. Incident cleanup succeeded; the remaining one user/organization/membership were removed with the tmpfs test database. The test backend was stopped. No persistent application database changes or migration runs occurred. Existing Keycloak/PostgreSQL services and volumes remained running/intact. The approved clients and synthetic Keycloak test user were retained; their normal browser/server sessions expire under existing realm policy.

## Tests and self-review

| Exact command | Result |
| --- | --- |
| `python3 scripts/keycloak_local_setup.py --apply` | Approved additions created/verified; repeating it verified existing objects without changing them. Initial verification stopped on Keycloak-added mapper defaults; the comparison was corrected and the additions/audit then passed. |
| `python3 scripts/keycloak_local_setup.py` | PASS: both client policies, dedicated mappers/scopes, and CLI isolation verified. |
| `python3 scripts/keycloak_pkce_smoke.py` | PASS: real browser Code + PKCE, signed-token API checks, wrong audience, and incident cleanup. |
| `python3 -m unittest discover -s scripts/tests -v` | PASS: 20 tests, no failures/skips. Covers PKCE generation, callback spoofing/duplicate parameters, local URL/redirect restrictions, safe error handling, claim/nonce checks, mapper/config isolation, and secret-containing/unsafe manifest rejection. |
| `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw clean test` (backend/) | PASS: 45 tests, zero failures/errors/skips. |
| `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw verify` (backend/) | PASS: 45 tests, zero failures/errors/skips; application JAR packaged. |
| `docker compose config --quiet`, `docker compose ps` (root) | PASS: existing configuration valid; persistent services remain on loopback. |
| `git diff --check` | PASS. |

The final standalone PKCE policy probes passed against real Keycloak. The first supplementary probe stopped safely because Keycloak returns an OAuth error redirect rather than a JSON 400; the final probe explicitly handles that redirect without following it. Docker reports the disposable tmpfs under HostConfig rather than Mounts; the cleanup guard was corrected to verify that metadata before removing only the labeled test runtime.

Self-review checked additive-only admin operations, exact redirect/grant/mapper restrictions, absence of shared human mappings, transient secret handling, callback/proxy/redirect boundaries, sanitized output, isolated test data, cleanup, and unchanged application/migrations. No JWT-like material was found in the local smoke/backend logs. No credentials or real user information are included in repository artifacts.

This completes local Keycloak human/service-account verification, including real incorrect-audience rejection. It is not production deployment, IAM, AWS, expired-token, wrong-issuer-token, real multi-human cross-tenant, or browser-platform compatibility testing. The existing tenant/RBAC tests still provide their documented PostgreSQL/simulated-user evidence. Do not infer deployment approval from this result.

Next checkpoint: review the local integration and reusable artifacts. Milestone 10 remains unstarted.
