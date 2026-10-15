import { defineConfig, devices } from "@playwright/test";
export default defineConfig({
  testDir: "./e2e",
  testMatch: "**/*.spec.ts",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 30000,
  reporter: "list",
  use: { baseURL: "http://127.0.0.1:8768", trace: "off", screenshot: "off" },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: [
    {
      command: "node e2e/mock-server.mjs",
      url: "http://127.0.0.1:18090/__health",
      reuseExistingServer: false,
    },
    {
      command:
        "node node_modules/next/dist/bin/next start --hostname 127.0.0.1 --port 8768",
      url: "http://127.0.0.1:8768/login",
      reuseExistingServer: false,
      env: {
        APP_ORIGIN: "http://127.0.0.1:8768",
        OIDC_ISSUER: "http://127.0.0.1:18090/realms/test",
        BACKEND_ORIGIN: "http://127.0.0.1:18090",
        ALLOW_LOCAL_HTTP: "true",
      },
    },
  ],
});
