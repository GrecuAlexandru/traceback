# Traceback frontend

Next.js App Router + React 19 + TypeScript + Meta Astryx Neutral.

From this directory:

```sh
npm ci
cp .env.example .env.local # Only when .env.local does not already exist.
npm run dev
```

Open <http://127.0.0.1:8765>. The exact address matches the previously approved
Keycloak test client's callback. Set the server-only `BACKEND_ORIGIN` to your approved
local backend. For verification against isolated data, use the disposable setup in
[the M16 report](../docs/M16_FRONTEND.md); starting a backend against the persistent
Compose database can apply its existing migrations and is not performed by M16.

```sh
npm run typecheck
npm run lint
npm run test
npm run build
npx playwright install chromium
npm run test:e2e
npm audit
npm run astryx -- theme build src/theme/traceback.source.ts --out src/theme/traceback.css --check
```

Browser tests launch a production frontend at loopback port 8768 and a deterministic
OIDC/REST fixture at 18090. They do not access Keycloak, the application database or
models. Screenshot evidence is saved outside the repository in `/tmp/traceback-m16-qa`.
The real in-app browser smoke test is documented separately and is not inferred from
mocked test results.

When editing tokens, regenerate the committed theme artifacts:

```sh
npm run astryx -- theme build src/theme/traceback.source.ts --out src/theme/traceback.css
```

Use `npm run astryx -- component <Name>` and `npm run astryx -- template --list` to
inspect actual APIs. The CLI initialized `frontend/AGENTS.md`; repository-root
Traceback instructions remain authoritative.
