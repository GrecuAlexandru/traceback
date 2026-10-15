import { describe, it, expect, beforeEach } from "vitest";
import { allowedRoute, boundedBody, sameOrigin } from "@/lib/server/boundary";
import { configuration } from "@/lib/server/config";
const org = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const item = "11111111-1111-4111-8111-111111111111";
beforeEach(() => {
  process.env.ALLOW_LOCAL_HTTP = "true";
  process.env.APP_ORIGIN = "http://127.0.0.1:8765";
});
describe("BFF trust boundary", () => {
  it.each([
    "http://attacker.test/api",
    "../me",
    "organizations/%2e%2e/me",
    "organizations/" + org + "/documents/../../me",
    "mcp",
    "actuator/health",
    "organizations/" +
      org +
      "/conversations/" +
      item +
      "/messages/" +
      item +
      "/citations/javascript:alert(1)",
  ])("rejects path %s", (path) => {
    expect(allowedRoute(path, "GET")).toBe(false);
  });
  it("allows exactly scoped supported verbs", () => {
    expect(
      allowedRoute(`organizations/${org}/incidents/${item}`, "PATCH"),
    ).toBe(true);
    expect(allowedRoute(`organizations/${org}/incidents/${item}`, "PUT")).toBe(
      false,
    );
    expect(allowedRoute(`organizations/${org}/search`, "POST")).toBe(false);
  });
  it("requires matching Origin and custom header on writes", () => {
    expect(
      sameOrigin(
        new Request("http://127.0.0.1:8765/api", {
          headers: { Origin: "https://evil.test", "X-Traceback-Request": "1" },
        }),
      ),
    ).toBe(false);
    expect(
      sameOrigin(
        new Request("http://127.0.0.1:8765/api", {
          headers: { Origin: "http://127.0.0.1:8765" },
        }),
      ),
    ).toBe(false);
    expect(
      sameOrigin(
        new Request("http://127.0.0.1:8765/api", {
          headers: {
            Origin: "http://127.0.0.1:8765",
            "X-Traceback-Request": "1",
          },
        }),
      ),
    ).toBe(true);
  });
  it("bounds unknown-length bodies before forwarding", async () => {
    await expect(
      boundedBody(
        new Request("http://localhost", { method: "POST", body: "12345" }),
        4,
      ),
    ).rejects.toThrow(RangeError);
    expect(
      await boundedBody(
        new Request("http://localhost", { method: "POST", body: "1234" }),
        4,
      ),
    ).toHaveLength(4);
  });
  it("rejects HTTP outside loopback and credential-bearing URLs", () => {
    process.env.BACKEND_ORIGIN = "http://evil.test";
    expect(() => configuration()).toThrow();
    process.env.BACKEND_ORIGIN = "https://user:secret@example.test";
    expect(() => configuration()).toThrow();
    delete process.env.BACKEND_ORIGIN;
  });
});
