import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import * as oidc from "openid-client";
import {
  createSession,
  getSession,
  sessions,
  logins,
  newLogin,
  consumeLogin,
  cookieOptions,
  dropSession,
} from "@/lib/server/session";
const fixture = vi.hoisted(() => ({
  id: undefined as string | undefined,
  refresh: vi.fn(),
}));
vi.mock("next/headers", () => ({
  cookies: async () => ({
    get: () => (fixture.id ? { value: fixture.id } : undefined),
  }),
}));
vi.mock("openid-client", async (importOriginal) => {
  const original = await importOriginal<typeof oidc>();
  return {
    ...original,
    discovery: vi.fn().mockResolvedValue({}),
    refreshTokenGrant: fixture.refresh,
  };
});
const identity = {
  issuer: "http://localhost:8180/realms/traceback",
  subject: "fixture-user",
  username: null,
};
const token = (seconds: number) =>
  ({
    access_token: "synthetic-access",
    refresh_token: "synthetic-refresh",
    expires_in: seconds,
    token_type: "Bearer",
    claims: () => ({ sub: identity.subject }),
  }) as unknown as Parameters<typeof createSession>[0];
beforeEach(() => {
  process.env.ALLOW_LOCAL_HTTP = "true";
  sessions.clear();
  logins.clear();
  fixture.id = undefined;
  fixture.refresh.mockReset();
  vi.stubGlobal(
    "fetch",
    vi
      .fn()
      .mockImplementation(
        async () => new Response(JSON.stringify(identity), { status: 200 }),
      ),
  );
  vi.useFakeTimers();
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});
describe("Server-managed sessions", () => {
  it("rotates opaque IDs and never places tokens in cookie values", () => {
    const a = createSession(token(300), identity),
      b = createSession(token(300), identity);
    expect(a).not.toBe(b);
    expect(a).not.toContain("synthetic");
    expect(cookieOptions()).toMatchObject({
      httpOnly: true,
      sameSite: "lax",
      path: "/",
    });
  });
  it("consumes a login exactly once and rejects expired state", () => {
    const login = newLogin();
    expect(consumeLogin(login.id)?.state).toBe(login.pending.state);
    expect(consumeLogin(login.id)).toBeUndefined();
    const expired = newLogin();
    vi.advanceTimersByTime(300001);
    expect(consumeLogin(expired.id)).toBeUndefined();
  });
  it("restores a valid session from an opaque cookie", async () => {
    fixture.id = createSession(token(300), identity);
    expect((await getSession()).session.identity.subject).toBe(
      identity.subject,
    );
  });
  it("single-flights concurrent refreshes", async () => {
    fixture.id = createSession(token(5), identity);
    fixture.refresh.mockResolvedValue(token(300));
    await Promise.all([getSession(), getSession(), getSession()]);
    expect(fixture.refresh).toHaveBeenCalledTimes(1);
  });
  it("expires when refresh fails or changes identity", async () => {
    fixture.id = createSession(token(5), identity);
    fixture.refresh.mockRejectedValue(new Error("Fixture failure"));
    await expect(getSession()).rejects.toThrow();
    expect(sessions.size).toBe(0);
    fixture.id = createSession(token(5), identity);
    fixture.refresh.mockResolvedValue({
      ...token(300),
      claims: () => ({ sub: "another-user" }),
    });
    await expect(getSession()).rejects.toThrow();
    expect(sessions.size).toBe(0);
  });
  it("enforces idle lifetime and invalidates logout", async () => {
    fixture.id = createSession(token(3600), identity);
    vi.advanceTimersByTime(1800001);
    await expect(getSession()).rejects.toThrow();
    fixture.id = createSession(token(300), identity);
    await dropSession();
    await expect(getSession()).rejects.toThrow();
  });
});

it("checks the backend identity even when refresh omits an ID token", async () => {
  fixture.id = createSession(token(5), identity);
  fixture.refresh.mockResolvedValue({ ...token(300), claims: () => undefined });
  await expect(getSession()).resolves.toBeDefined();
  fixture.id = createSession(token(5), identity);
  vi.mocked(fetch).mockResolvedValue(
    new Response(JSON.stringify({ ...identity, subject: "another-user" }), {
      status: 200,
    }),
  );
  await expect(getSession()).rejects.toThrow();
  expect(sessions.has(fixture.id)).toBe(false);
});
