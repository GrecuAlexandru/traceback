import "server-only";
import { randomBytes } from "node:crypto";
import * as oidc from "openid-client";
import { cookies } from "next/headers";
import { configuration } from "./config";
export type Identity = {
  subject: string;
  username: string | null;
  issuer: string;
};
type Session = {
  token: string;
  refresh?: string;
  identity: Identity;
  expires: number;
  absolute: number;
  lastSeen: number;
  refreshing?: Promise<void>;
};
type Pending = {
  state: string;
  nonce: string;
  verifier: string;
  expires: number;
};
// One local Node process. No shared or persistent token cache. Restart expires sessions.
const globalStore = globalThis as typeof globalThis & {
  tracebackSessions?: Map<string, Session>;
  tracebackLogins?: Map<string, Pending>;
};
export const sessions = (globalStore.tracebackSessions ??= new Map());
export const logins = (globalStore.tracebackLogins ??= new Map());
export const sessionCookie = process.env.APP_ORIGIN?.startsWith("https://")
  ? "__Host-traceback_session"
  : "traceback_session";
export const loginCookie = process.env.APP_ORIGIN?.startsWith("https://")
  ? "__Host-traceback_login"
  : "traceback_login";
let discovery: Promise<oidc.Configuration> | undefined;
export function oidcConfig() {
  if (!discovery) {
    const c = configuration();
    discovery = oidc.discovery(
      new URL(c.issuer),
      c.clientId,
      undefined,
      oidc.None(),
      {
        timeout: 10,
        ...(c.localHttp ? { execute: [oidc.allowInsecureRequests] } : {}),
      },
    );
    discovery.catch(() => {
      discovery = undefined;
    });
  }
  return discovery;
}
export function cookieOptions(maxAge = 28800) {
  return {
    httpOnly: true,
    secure: configuration().secure,
    sameSite: "lax" as const,
    path: "/",
    maxAge,
  };
}
export function cleanup() {
  const now = Date.now();
  for (const [id, s] of sessions)
    if (s.absolute <= now || now - s.lastSeen > 1800000) sessions.delete(id);
  for (const [id, p] of logins) if (p.expires <= now) logins.delete(id);
}
export function newLogin() {
  cleanup();
  if (logins.size >= 100) throw new Error("Login capacity");
  const id = randomBytes(32).toString("base64url");
  const pending = {
    state: oidc.randomState(),
    nonce: oidc.randomNonce(),
    verifier: oidc.randomPKCECodeVerifier(),
    expires: Date.now() + 300000,
  };
  logins.set(id, pending);
  return { id, pending };
}
export function consumeLogin(id: string | undefined) {
  cleanup();
  if (!id) return;
  const pending = logins.get(id);
  logins.delete(id);
  return pending;
}
export function createSession(
  tokens: oidc.TokenEndpointResponse & oidc.TokenEndpointResponseHelpers,
  identity: Identity,
) {
  cleanup();
  if (sessions.size >= 1000) throw new Error("Session capacity");
  const id = randomBytes(32).toString("base64url");
  sessions.set(id, {
    token: tokens.access_token,
    refresh: tokens.refresh_token,
    identity,
    expires: Date.now() + (tokens.expires_in ?? 0) * 1000,
    absolute: Date.now() + 28800000,
    lastSeen: Date.now(),
  });
  return id;
}
export class SessionExpired extends Error {}
export async function getSession() {
  cleanup();
  const id = (await cookies()).get(sessionCookie)?.value;
  const s = id ? sessions.get(id) : undefined;
  if (!s) throw new SessionExpired();
  if (s.expires < Date.now() + 30000) {
    if (!s.refresh) {
      sessions.delete(id!);
      throw new SessionExpired();
    }
    s.refreshing ??= (async () => {
      try {
        const tokens = await oidc.refreshTokenGrant(
          await oidcConfig(),
          s.refresh!,
        );
        const claims = tokens.claims();
        if (claims && claims.sub !== s.identity.subject)
          throw new Error("Identity changed");
        // Refresh responses may omit an ID token. Recheck the REST decoder's
        // authenticated identity before accepting any rotated access token.
        const response = await fetch(configuration().backend + "/api/v1/me", {
          headers: { Authorization: `Bearer ${tokens.access_token}` },
          cache: "no-store",
          redirect: "error",
          signal: AbortSignal.timeout(10000),
        });
        if (!response.ok) throw new Error("Refresh identity unavailable");
        const refreshed = (await response.json()) as Identity;
        if (
          refreshed.subject !== s.identity.subject ||
          refreshed.issuer !== s.identity.issuer
        )
          throw new Error("Identity changed");
        s.token = tokens.access_token;
        s.refresh = tokens.refresh_token ?? s.refresh;
        s.expires = Date.now() + (tokens.expires_in ?? 0) * 1000;
        if (s.expires <= Date.now()) throw new Error("Expired token");
      } catch {
        sessions.delete(id!);
        throw new SessionExpired();
      } finally {
        s.refreshing = undefined;
      }
    })();
    await s.refreshing;
  }
  // A concurrent logout must not revive or return an invalidated session.
  if (sessions.get(id!) !== s) throw new SessionExpired();
  s.lastSeen = Date.now();
  return { id: id!, session: s };
}
export async function dropSession() {
  const id = (await cookies()).get(sessionCookie)?.value;
  const s = id ? sessions.get(id) : undefined;
  if (id) sessions.delete(id);
  return s;
}
