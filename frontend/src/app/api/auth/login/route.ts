import * as oidc from "openid-client";
import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { configuration } from "@/lib/server/config";
import {
  cookieOptions,
  loginCookie,
  newLogin,
  oidcConfig,
} from "@/lib/server/session";
export async function GET() {
  try {
    const { id, pending } = newLogin();
    const url = oidc.buildAuthorizationUrl(await oidcConfig(), {
      redirect_uri: configuration().origin + "/callback",
      scope: "openid",
      state: pending.state,
      nonce: pending.nonce,
      code_challenge: await oidc.calculatePKCECodeChallenge(pending.verifier),
      code_challenge_method: "S256",
    });
    (await cookies()).set(loginCookie, id, cookieOptions(300));
    return NextResponse.redirect(url);
  } catch {
    return NextResponse.redirect(
      configuration().origin + "/login?error=authentication_unavailable",
    );
  }
}
