import { configuration } from "@/lib/server/config";
import { operationalFailure } from "@/lib/server/operations";
import { getSession, sessions, SessionExpired } from "@/lib/server/session";
import {
  allowedRoute,
  boundedBody,
  problem,
  sameOrigin,
} from "@/lib/server/boundary";
async function proxy(
  request: Request,
  context: { params: Promise<{ path: string[] }> },
) {
  const path = (await context.params).path.join("/");
  if (!allowedRoute(path, request.method)) return problem(404, "NOT_FOUND");
  const write = request.method !== "GET";
  if (write && !sameOrigin(request)) return problem(403, "CSRF_REJECTED");
  try {
    const { id, session } = await getSession();
    const c = configuration();
    const query = new URL(request.url).searchParams;
    for (const key of query.keys())
      if (
        !["page", "size", "status", "version", "q", "limit", "mode"].includes(
          key,
        )
      )
        return problem(400, "INVALID_QUERY");
    const target = new URL(`/api/v1/${path}`, c.backend);
    target.search = query.toString();
    const contentType = request.headers.get("content-type") ?? "";
    const multipart =
      write && path.includes("/documents") && request.method === "POST";
    if (
      write &&
      request.method !== "DELETE" &&
      !(multipart
        ? contentType.startsWith("multipart/form-data; boundary=")
        : contentType.startsWith("application/json"))
    )
      return problem(415, "UNSUPPORTED_MEDIA_TYPE");
    const body = write
      ? await boundedBody(
          request,
          multipart ? c.maxFileBytes + 1024 * 1024 : 131072,
        )
      : undefined;
    const headers = new Headers({ Authorization: `Bearer ${session.token}` });
    if (body) headers.set("Content-Type", contentType);
    const response = await fetch(target, {
      method: request.method,
      headers,
      body,
      redirect: "error",
      cache: "no-store",
      signal: write
        ? AbortSignal.timeout(path.endsWith("/messages") ? 180000 : 45000)
        : AbortSignal.any([request.signal, AbortSignal.timeout(45000)]),
    });
    if (response.status === 401) sessions.delete(id);
    if (!response.ok) {
      if (response.status >= 500) operationalFailure("bff");
      let code = "REQUEST_FAILED";
      try {
        const data = await response.json();
        if (typeof data.code === "string" && /^[A-Z_]{1,80}$/.test(data.code))
          code = data.code;
        else if (
          typeof data.errorCode === "string" &&
          /^[A-Z_]{1,80}$/.test(data.errorCode)
        )
          code = data.errorCode;
      } catch {
        /* Only fixed codes are returned. */
      }
      return problem(response.status, code);
    }
    if (response.status === 204)
      return new Response(null, {
        status: 204,
        headers: { "Cache-Control": "no-store" },
      });
    const resultHeaders = new Headers({
      "Cache-Control": "no-store",
      "X-Content-Type-Options": "nosniff",
    });
    if (path.endsWith("/content")) {
      resultHeaders.set("Content-Type", "application/octet-stream");
      resultHeaders.set(
        "Content-Disposition",
        response.headers.get("content-disposition") ?? "attachment",
      );
      resultHeaders.set(
        "Content-Security-Policy",
        "default-src 'none'; sandbox",
      );
    } else resultHeaders.set("Content-Type", "application/json");
    return new Response(response.body, {
      status: response.status,
      headers: resultHeaders,
    });
  } catch (error) {
    if (error instanceof SessionExpired) return problem(401, "SESSION_EXPIRED");
    if (error instanceof RangeError) return problem(413, "BODY_LIMIT");
    if (!request.signal.aborted) operationalFailure("bff");
    return problem(503, "DEPENDENCY_UNAVAILABLE");
  }
}
export { proxy as GET, proxy as POST, proxy as PATCH, proxy as DELETE };
