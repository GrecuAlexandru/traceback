import process from "node:process";
import { URL } from "node:url";
export function configuration() {
  const production = process.env.TRACEBACK_ENVIRONMENT === "production";
  if (production) {
    if (
      process.env.ALLOW_LOCAL_HTTP === "true" ||
      process.env.FRONTEND_REPLICAS !== "1" ||
      process.env.SESSION_STORE !== "memory-single-process" ||
      (process.env.WEB_CONCURRENCY && process.env.WEB_CONCURRENCY !== "1") ||
      !process.env.APP_ORIGIN ||
      !process.env.BACKEND_ORIGIN ||
      !process.env.OIDC_ISSUER ||
      !process.env.OIDC_CLIENT_ID ||
      process.env.OIDC_CLIENT_ID === "traceback-web-test"
    )
      throw new Error("Unsafe or incomplete production configuration");
  }
  const localHttp = process.env.ALLOW_LOCAL_HTTP === "true";
  const url = (value) => {
    const parsed = new URL(value);
    if (parsed.username || parsed.password || parsed.search || parsed.hash)
      throw new Error("Invalid server configuration");
    if (
      parsed.protocol !== "https:" &&
      !(
        localHttp &&
        parsed.protocol === "http:" &&
        ["127.0.0.1", "localhost", "[::1]"].includes(parsed.hostname)
      )
    )
      throw new Error("HTTPS required");
    return parsed;
  };
  const app = url(process.env.APP_ORIGIN ?? "http://127.0.0.1:8765");
  const backend = url(process.env.BACKEND_ORIGIN ?? "http://127.0.0.1:8080");
  const issuer = url(
    process.env.OIDC_ISSUER ?? "http://localhost:8180/realms/traceback",
  );
  if (
    production &&
    [app, backend, issuer].some((value) =>
      ["localhost", "127.0.0.1", "[::1]"].includes(value.hostname),
    )
  )
    throw new Error("Production origins require stable non-loopback hosts");
  if (app.pathname !== "/" || backend.pathname !== "/")
    throw new Error("Origins cannot contain paths");
  const maxFileBytes = Number(process.env.DOCUMENT_MAX_FILE_BYTES ?? 10485760);
  const maxQuestionBytes = Number(process.env.CHAT_MAX_QUESTION_BYTES ?? 1000);
  if (
    !Number.isInteger(maxFileBytes) ||
    maxFileBytes < 1 ||
    maxFileBytes > 52428800 ||
    !Number.isInteger(maxQuestionBytes) ||
    maxQuestionBytes < 1 ||
    maxQuestionBytes > 16000
  )
    throw new Error("Invalid resource limits");
  return {
    origin: app.origin,
    backend: backend.origin,
    issuer: issuer.href.replace(/\/$/, ""),
    clientId: process.env.OIDC_CLIENT_ID ?? "traceback-web-test",
    localHttp,
    secure: app.protocol === "https:",
    maxFileBytes,
    maxQuestionBytes,
  };
}
