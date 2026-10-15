import * as oidc from "openid-client";
import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { configuration } from "@/lib/server/config";
import {
  consumeLogin,
  cookieOptions,
  createSession,
  dropSession,
  loginCookie,
  oidcConfig,
  sessionCookie,
  type Identity,
} from "@/lib/server/session";
export async function GET(request: Request) {
  const c = configuration();
  const jar = await cookies();
  const pending = consumeLogin(jar.get(loginCookie)?.value);
  jar.delete(loginCookie);
  try {
    if (!pending) throw new Error("Expired login");
    // Fixed origin, never Host/Forwarded-derived. Library checks state, issuer, PKCE and ID-token nonce.
    const callback = new URL(c.origin + "/callback");
    callback.search = new URL(request.url).search;
    const tokens = await oidc.authorizationCodeGrant(
      await oidcConfig(),
      callback,
      {
        pkceCodeVerifier: pending.verifier,
        expectedState: pending.state,
        expectedNonce: pending.nonce,
        idTokenExpected: true,
      },
    );
    const response = await fetch(c.backend + "/api/v1/me", {
      headers: { Authorization: `Bearer ${tokens.access_token}` },
      cache: "no-store",
      redirect: "error",
      signal: AbortSignal.timeout(10000),
    });
    if (!response.ok) throw new Error("Incompatible access token");
    const identity = (await response.json()) as Identity;
    if (
      identity.subject !== tokens.claims()?.sub ||
      identity.issuer !== c.issuer
    )
      throw new Error("Identity mismatch");
    // Backend verifies issuer-controlled human classification; /me alone also accepts service accounts.
    const memberships = await fetch(c.backend + "/api/v1/organizations", {
      headers: { Authorization: `Bearer ${tokens.access_token}` },
      cache: "no-store",
      redirect: "error",
      signal: AbortSignal.timeout(10000),
    });
    if (!memberships.ok) throw new Error("Human token required");
    await dropSession();
    jar.set(sessionCookie, createSession(tokens, identity), cookieOptions());
    return NextResponse.redirect(c.origin + "/");
  } catch {
    return NextResponse.redirect(
      c.origin + "/login?error=authentication_failed",
    );
  }
}
