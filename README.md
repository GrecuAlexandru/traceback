# Traceback

A Java/Spring Boot engineering incident and knowledge platform with tenant-scoped RBAC,
immutable documents, durable ingestion, hybrid retrieval, private grounded chat and
read-only authenticated MCP. The Next.js/Astryx BFF keeps OIDC tokens server-side.

M9A–M16 are approved for local development. M17 packaging/readiness is implemented and
verified locally; **AWS deployment remains blocked and unapproved**. One API writer and
one frontend process are required. No public demo or production-security claim is made.

- [Architecture and current boundaries](docs/ARCHITECTURE.md)
- [Milestones and scope](docs/ROADMAP.md)
- [Local frontend setup and behavior](docs/M16_FRONTEND.md)
- [M17 deployment, cost estimates, tests, recovery and blockers](docs/M17_DEPLOYMENT.md)
- [Architecture decisions](docs/DECISIONS/README.md)

Java 21, Docker/cgroup v2 and Node 22+ are needed for local verification. Run backend
`./mvnw clean test` / `./mvnw verify` from `backend/`; frontend `npm ci`, typecheck,
lint, tests, build and e2e from `frontend/`. Real inference uses separately approved
installed models, never automatic downloads or cloud providers. Existing developer
Compose configuration and volumes are preserved; use the M17 guide for disposable
production-like container checks. Do not point tests at persistent user databases.
