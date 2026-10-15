import { configuration } from "@/lib/server/config";
import { getSession, SessionExpired } from "@/lib/server/session";
import { problem } from "@/lib/server/boundary";
export async function GET() {
  try {
    const { session } = await getSession();
    const c = configuration();
    return Response.json(
      {
        identity: session.identity,
        expiresAt: session.expires,
        maxFileBytes: c.maxFileBytes,
        maxQuestionBytes: c.maxQuestionBytes,
      },
      { headers: { "Cache-Control": "no-store" } },
    );
  } catch (error) {
    return problem(
      error instanceof SessionExpired ? 401 : 503,
      "SESSION_UNAVAILABLE",
    );
  }
}
