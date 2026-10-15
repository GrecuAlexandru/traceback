import * as oidc from "openid-client";
import { cookies } from "next/headers";
import { dropSession, oidcConfig, sessionCookie } from "@/lib/server/session";
import { problem, sameOrigin } from "@/lib/server/boundary";
export async function POST(request: Request) {
  if (!sameOrigin(request)) return problem(403, "CSRF_REJECTED");
  const old = await dropSession();
  (await cookies()).delete(sessionCookie);
  try {
    const config = await oidcConfig();
    if (old?.refresh)
      await oidc
        .tokenRevocation(config, old.refresh, {
          token_type_hint: "refresh_token",
        })
        .catch(() => {});
    // No token in URL. Keycloak may ask the human to confirm SSO logout.
    const url = oidc.buildEndSessionUrl(config, {
      client_id: config.clientMetadata().client_id,
    });
    return Response.json(
      { logoutUrl: url.href },
      { headers: { "Cache-Control": "no-store" } },
    );
  } catch {
    return Response.json(
      { logoutUrl: "/login" },
      { headers: { "Cache-Control": "no-store" } },
    );
  }
}
