import { NextResponse, type NextRequest } from "next/server";
import { randomBytes } from "node:crypto";
export function proxy(request: NextRequest) {
  if (
    process.env.TRACEBACK_ENVIRONMENT === "production" &&
    request.nextUrl.pathname !== "/api/health" &&
    request.headers.get("host") !== new URL(process.env.APP_ORIGIN!).host
  ) {
    return NextResponse.json(
      { status: 403, code: "HOST_REJECTED" },
      { status: 403, headers: { "Cache-Control": "no-store" } },
    );
  }
  const nonce = randomBytes(16).toString("base64");
  const development = process.env.NODE_ENV !== "production";
  const csp = `default-src 'self'; script-src 'self' 'nonce-${nonce}'${development ? " 'unsafe-eval'" : ""}; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'${development ? " ws:" : ""}; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self';`;
  const headers = new Headers(request.headers);
  headers.set("x-nonce", nonce);
  headers.set("Content-Security-Policy", csp);
  const response = NextResponse.next({ request: { headers } });
  response.headers.set("Content-Security-Policy", csp);
  response.headers.set("Cache-Control", "no-store");
  return response;
}
export const config = {
  matcher: ["/((?!_next/static|_next/image|favicon.ico).*)"],
};
