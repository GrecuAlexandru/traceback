import "server-only";
import { configuration } from "./config";
export function sameOrigin(request: Request) {
  return (
    request.headers.get("origin") === configuration().origin &&
    request.headers.get("x-traceback-request") === "1"
  );
}
const uuid =
  "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
const org = `organizations/${uuid}`;
const rules: [RegExp, string[]][] = [
  [/^me$/, ["GET"]],
  [/^organizations$/, ["GET", "POST"]],
  [new RegExp(`^${org}/incidents$`), ["GET", "POST"]],
  [new RegExp(`^${org}/incidents/${uuid}$`), ["GET", "PATCH", "DELETE"]],
  [new RegExp(`^${org}/documents$`), ["GET", "POST"]],
  [new RegExp(`^${org}/documents/${uuid}$`), ["GET", "PATCH", "DELETE"]],
  [new RegExp(`^${org}/documents/${uuid}/content$`), ["GET"]],
  [new RegExp(`^${org}/documents/${uuid}/revisions$`), ["GET", "POST"]],
  [
    new RegExp(
      `^${org}/documents/${uuid}/revisions/${uuid}/(content|ingestion|indexing)$`,
    ),
    ["GET"],
  ],
  [new RegExp(`^${org}/search$`), ["GET"]],
  [new RegExp(`^${org}/conversations$`), ["GET", "POST"]],
  [new RegExp(`^${org}/conversations/${uuid}$`), ["GET", "PATCH", "DELETE"]],
  [new RegExp(`^${org}/conversations/${uuid}/messages$`), ["POST"]],
  [new RegExp(`^${org}/conversations/${uuid}/messages/${uuid}$`), ["GET"]],
  [
    new RegExp(
      `^${org}/conversations/${uuid}/messages/${uuid}/citations/S[1-9][0-9]{0,2}$`,
    ),
    ["GET"],
  ],
];
export function allowedRoute(path: string, method: string) {
  return rules.some(
    ([pattern, methods]) => pattern.test(path) && methods.includes(method),
  );
}
export async function boundedBody(request: Request, limit: number) {
  if (Number(request.headers.get("content-length")) > limit)
    throw new RangeError();
  if (!request.body) return undefined;
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.length;
      if (total > limit) {
        await reader.cancel();
        throw new RangeError();
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const body = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    body.set(chunk, offset);
    offset += chunk.length;
  }
  return body;
}
export function problem(status: number, code: string) {
  return Response.json(
    { status, code, title: code },
    { status, headers: { "Cache-Control": "no-store" } },
  );
}
