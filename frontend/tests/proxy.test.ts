import { afterEach, expect, it, vi } from "vitest";
import { NextRequest } from "next/server";
import { proxy } from "@/proxy";
afterEach(() => vi.unstubAllEnvs());
it("rejects foreign production Hosts on pages, callbacks and BFF routes", () => {
  vi.stubEnv("TRACEBACK_ENVIRONMENT", "production");
  vi.stubEnv("APP_ORIGIN", "https://app.example.test");
  for (const path of ["/login", "/callback", "/api/session"]) {
    expect(
      proxy(
        new NextRequest("https://app.example.test" + path, {
          headers: { host: "evil.example.test" },
        }),
      ).status,
    ).toBe(403);
    expect(
      proxy(
        new NextRequest("https://app.example.test" + path, {
          headers: { host: "app.example.test" },
        }),
      ).status,
    ).toBe(200);
  }
  expect(proxy(new NextRequest("http://127.0.0.1/api/health")).status).toBe(
    200,
  );
});
