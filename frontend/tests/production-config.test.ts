import { afterEach, describe, expect, it, vi } from "vitest";
import { configuration } from "@/lib/server/config";

function production() {
  for (const [key, value] of Object.entries({
    TRACEBACK_ENVIRONMENT: "production",
    ALLOW_LOCAL_HTTP: "false",
    FRONTEND_REPLICAS: "1",
    SESSION_STORE: "memory-single-process",
    APP_ORIGIN: "https://app.example.test",
    BACKEND_ORIGIN: "https://api.internal.example.test",
    OIDC_ISSUER: "https://auth.example.test/realms/traceback",
    OIDC_CLIENT_ID: "traceback-web",
  }))
    vi.stubEnv(key, value);
}
afterEach(() => vi.unstubAllEnvs());
describe("production BFF configuration", () => {
  it("requires explicit singleton sessions and secure origins", () => {
    production();
    expect(configuration().secure).toBe(true);
    vi.stubEnv("FRONTEND_REPLICAS", "2");
    expect(() => configuration()).toThrow();
  });
  it.each([
    ["ALLOW_LOCAL_HTTP", "true"],
    ["SESSION_STORE", "redis-unimplemented"],
    ["WEB_CONCURRENCY", "2"],
    ["OIDC_CLIENT_ID", "traceback-web-test"],
    ["APP_ORIGIN", "http://127.0.0.1:8765"],
    ["OIDC_ISSUER", "http://localhost:8180"],
    ["BACKEND_ORIGIN", "http://api.internal"],
  ])("rejects unsafe %s", (key, value) => {
    production();
    vi.stubEnv(key, value);
    expect(() => configuration()).toThrow();
  });
});
