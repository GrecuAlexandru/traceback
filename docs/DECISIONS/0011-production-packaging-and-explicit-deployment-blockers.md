# ADR-0011 — Production packaging and explicit deployment blockers

- Status: Accepted for the requested M17 local implementation; AWS topology remains Proposed
- Date: 2026-10-15
- Extends: ADR-0001, ADR-0002, ADR-0003, ADR-0006, ADR-0007, ADR-0010

## Context

The approved local system has durable worker leases but one API upload-recovery owner,
process-local BFF sessions, immutable LOCAL revision providers, Docker/cgroup PDF isolation
and local CPU-only model adapters. Fargate packaging alone cannot remove these constraints.
The M17 request authorizes local implementation, testing and infrastructure design, with
no paid provisioning, public deployment, model change or real-content migration.

## Decision

Build one Java artifact with API and worker image targets, a separate isolated parser and
a standalone non-root Next.js image. Default images to explicit fail-closed production
configuration. Keep one API writer under PostgreSQL advisory ownership and one BFF process
under opaque in-memory sessions. Use stop-before-start releases; document maintenance/
re-login rather than claim overlapping API rollout or multi-replica session correctness.

Preserve immutable V1–V8 and provider-aware history. Optional S3-only startup must reject
any LOCAL inventory. Introduce a one-shot Flyway command separate from runtime credentials
and restricted API/worker grants (including the minimal column privilege for worker row
locks). Add explicit region/default-credential-chain AWS clients and configurable SSE-KMS;
emulator mode remains local-only with dummy credentials. Production rejects development
issuer/DB defaults, schema auto-update/runtime migration, MCP and unsafe origin/topology.

Implement Terraform foundations for private managed data, networking, storage, queues,
registry/roles/logging/alarms. Use mocked provider tests and static checks; no state backend
or AWS mutation is activated. Do not generate a running ECS service that cannot provide
the accepted PDF boundary. AWS compute/IdP/ingress/secret injection/model operations are
separately reviewed prerequisites. CI creates local artifacts/review bundles and has no
deploy command; real publication/role assumption needs explicit user approval.

Treat a Fargate-compatible PDF adapter or dedicated trusted sandbox host design as a
production deployment blocker. A local socket-based sandbox passing tests is not cloud
validation. Never weaken cgroup/network/capability checks or pass credentials to the parser.
Distributed API recovery, shared sessions and legacy storage-location cutover need new
reviewed designs/migrations before enabling corresponding scaling/migration behavior.

## Alternatives and consequences

A large document/session redesign is unnecessary for a low-traffic initial topology and
would require additional architecture approval. Redis adds a paid system before a second
frontend replica is justified. An all-Fargate claim would hide required daemon isolation.
An all-in-one database/model/app VM is cheaper but couples capacity and disaster recovery;
private RDS/S3/SQS is the proposed durable-data baseline. No additional queue/microservice
framework is introduced. Singleton restrictions reduce availability and remain explicit.

The local production-like project uses synthetic disposable services, a stable shared
loopback namespace, separate ports/volumes and optional read-only existing model volumes.
This tests images/authentication/leases/source safety, not AWS IAM/durability/TLS. A real
region/account/domain/budget and final runtime isolation must be approved before deployment.

## Implementation and evidence

See [M17 deployment report](../M17_DEPLOYMENT.md) for files, exact commands, real versus
fixture verification, costs, runbooks and remaining blockers. No real cloud resource,
production credential, development realm, existing document or historical migration was
modified; no commit/push/deployment occurred. Cloud proposals in that report remain
unverified until the explicit approval and evidence checklist is completed.

**Persistent demo follow-up (user requested, 2026-10-15):** A separate `traceback-demo`
project persists PostgreSQL, Keycloak identity and private LOCAL originals with named
volumes. It reuses the accepted adapters, singleton runtime and installed models. The
M17 verification project remains disposable; no cloud readiness or deployment decision
changes. See [local demo](../LOCAL_DEMO.md).
