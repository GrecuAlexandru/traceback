/* global URL, URLSearchParams, Response, Buffer */
// Deterministic, loopback-only test OIDC issuer + REST fixture. Never imported by the app.
import http from "node:http";
import { randomUUID, createHash } from "node:crypto";
import { generateKeyPair, SignJWT, exportJWK } from "jose";
const origin = "http://127.0.0.1:18090",
  issuer = origin + "/realms/test";
const { privateKey, publicKey } = await generateKeyPair("RS256");
const jwk = await exportJWK(publicKey);
jwk.kid = "fixture";
jwk.use = "sig";
const A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
  B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
  I = "11111111-1111-4111-8111-111111111111",
  D = "22222222-2222-4222-8222-222222222222",
  R = "33333333-3333-4333-8333-333333333333",
  C = "44444444-4444-4444-8444-444444444444";
const now = "2026-10-14T12:00:00Z";
let state;
const codes = new Map();
const tokens = new Set();
function reset() {
  state = {
    role: "ADMIN",
    conflict: false,
    unavailable: false,
    unauthorized: false,
    redacted: false,
    turnTimeout: false,
    turnPosts: [],
    incidents: [
      {
        id: I,
        organizationId: A,
        title: "Connection pool exhaustion",
        description:
          "HikariPool-1 timed out. Reduce the pool to 20 and close leaked connections.",
        status: "OPEN",
        version: 2,
        createdAt: now,
      },
    ],
    documents: [
      {
        id: D,
        organizationId: A,
        title: "Database recovery runbook",
        createdBy: "fixture-user",
        status: "AVAILABLE",
        version: 1,
        createdAt: now,
        modifiedAt: now,
        currentRevision: {
          id: R,
          organizationId: A,
          documentId: D,
          revisionNumber: 1,
          originalFilename: "database-runbook.md",
          mediaType: "text/markdown",
          fileSize: 240,
          checksum: "fixture-checksum",
          uploadedBy: "fixture-user",
          status: "AVAILABLE",
          createdAt: now,
        },
      },
    ],
    conversations: [
      {
        id: C,
        organizationId: A,
        title: "Investigating the pool timeout",
        createdAt: now,
        updatedAt: now,
      },
    ],
    messages: [],
    turns: new Map(),
    revisions: new Map(),
  };
  state.revisions.set(D, [state.documents[0].currentRevision]);
}
reset();
const page = (items, url, document = false) => {
  const p = Number(url.searchParams.get("page") ?? 0),
    s = Number(url.searchParams.get("size") ?? 20);
  return {
    [document ? "content" : "items"]: items.slice(p * s, p * s + s),
    page: p,
    size: s,
    totalElements: items.length,
    totalPages: Math.ceil(items.length / s),
    hasNext: (p + 1) * s < items.length,
  };
};
function citation() {
  return {
    reference: "S1",
    documentId: D,
    revisionId: R,
    chunkId: "55555555-5555-4555-8555-555555555555",
    generationId: "66666666-6666-4666-8666-666666666666",
    title: "Database recovery runbook",
    section: "Connection pool",
    pageStart: null,
    pageEnd: null,
    excerpt:
      "For HikariPool-1 timeout, reduce the pool to 20 and close leaked connections.",
  };
}
const answer =
  "Reduce the pool to **20** and close leaked connections. [S1]\n\n```java\nconfig.setMaximumPoolSize(20);\n```\n\n| Check | Expected |\n| --- | --- |\n| Pool | 20 |";
function complete(turn) {
  turn.status = "ANSWERED";
  turn.answer = answer;
  turn.citations = [citation()];
  turn.completedAt = now;
  const m = state.messages.find((m) => m.id === turn.assistantMessageId);
  Object.assign(m, {
    status: "ANSWERED",
    content: answer,
    citations: [citation()],
    completedAt: now,
  });
}
const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, origin);
  const parts = url.pathname.split("/").filter(Boolean);
  let raw = Buffer.alloc(0);
  for await (const chunk of req) raw = Buffer.concat([raw, chunk]);
  const json = (data, status = 200) => {
    res.writeHead(status, {
      "Content-Type": "application/json",
      "Cache-Control": "no-store",
    });
    res.end(JSON.stringify(data));
  };
  const error = (status, code = "REQUEST_FAILED") =>
    json({ code, status }, status);
  let body = {};
  if (req.headers["content-type"]?.startsWith("application/json") && raw.length)
    body = JSON.parse(raw.toString());
  if (url.pathname === "/__health") return json({ ready: true });
  if (url.pathname === "/__reset") {
    reset();
    return json({ ok: true });
  }
  if (url.pathname === "/__control") {
    Object.assign(state, body);
    return json({ ok: true });
  }
  if (url.pathname === "/__state")
    return json({
      turnPosts: state.turnPosts,
      questions: state.messages.filter((m) => m.role === "USER").length,
    });
  if (
    url.pathname ===
    issuer.replace(origin, "") + "/.well-known/openid-configuration"
  )
    return json({
      issuer,
      authorization_endpoint: issuer + "/auth",
      token_endpoint: issuer + "/token",
      jwks_uri: issuer + "/jwks",
      end_session_endpoint: issuer + "/logout",
      revocation_endpoint: issuer + "/revoke",
      response_types_supported: ["code"],
      subject_types_supported: ["public"],
      id_token_signing_alg_values_supported: ["RS256"],
      code_challenge_methods_supported: ["S256"],
      authorization_response_iss_parameter_supported: true,
    });
  if (url.pathname.endsWith("/jwks")) return json({ keys: [jwk] });
  if (url.pathname.endsWith("/auth")) {
    const redirect = url.searchParams.get("redirect_uri");
    if (
      redirect !== "http://127.0.0.1:8768/callback" ||
      url.searchParams.get("code_challenge_method") !== "S256"
    )
      return error(400);
    const code = randomUUID();
    codes.set(code, {
      nonce: url.searchParams.get("nonce"),
      challenge: url.searchParams.get("code_challenge"),
    });
    const target = new URL(redirect);
    target.searchParams.set("code", code);
    target.searchParams.set("state", url.searchParams.get("state"));
    target.searchParams.set("iss", issuer);
    res.writeHead(302, { Location: target.href });
    return res.end();
  }
  if (url.pathname.endsWith("/token")) {
    const form = new URLSearchParams(raw.toString());
    let nonce;
    if (form.get("grant_type") === "authorization_code") {
      const code = codes.get(form.get("code"));
      codes.delete(form.get("code"));
      if (
        !code ||
        createHash("sha256")
          .update(form.get("code_verifier") ?? "")
          .digest("base64url") !== code.challenge
      )
        return error(400);
      nonce = code.nonce;
    } else if (form.get("grant_type") !== "refresh_token") return error(400);
    const access = randomUUID();
    tokens.add(access);
    const id = await new SignJWT({
      nonce,
      preferred_username: "Fixture engineer",
    })
      .setProtectedHeader({ alg: "RS256", kid: "fixture" })
      .setIssuer(issuer)
      .setSubject("fixture-user")
      .setAudience("traceback-web-test")
      .setIssuedAt()
      .setExpirationTime("10m")
      .sign(privateKey);
    return json({
      access_token: access,
      token_type: "Bearer",
      expires_in: 300,
      refresh_token: randomUUID(),
      id_token: id,
    });
  }
  if (url.pathname.endsWith("/revoke")) return json({});
  if (url.pathname.endsWith("/logout")) {
    res.writeHead(302, { Location: "http://127.0.0.1:8768/login" });
    return res.end();
  }
  if (!url.pathname.startsWith("/api/v1/")) return error(404);
  if (
    !tokens.has(req.headers.authorization?.replace("Bearer ", "")) ||
    state.unauthorized
  )
    return error(401);
  if (parts[2] === "me")
    return json({
      subject: "fixture-user",
      username: "Fixture engineer",
      issuer,
    });
  if (parts.length === 3 && parts[2] === "organizations")
    return json([
      { id: A, name: "Platform engineering", role: state.role, createdAt: now },
      { id: B, name: "Observability", role: "VIEWER", createdAt: now },
    ]);
  const org = parts[3],
    area = parts[4],
    id = parts[5];
  if (![A, B].includes(org)) return error(403);
  if (
    req.method !== "GET" &&
    area !== "conversations" &&
    (org === B || state.role === "VIEWER")
  )
    return error(403);
  if (state.unavailable) return error(503);
  if (area === "incidents") {
    if (org === B)
      return json(
        page(
          [
            {
              ...state.incidents[0],
              id: "77777777-7777-4777-8777-777777777777",
              organizationId: B,
              title: "Observability tenant incident",
            },
          ],
          url,
        ),
      );
    if (!id) {
      if (req.method === "GET")
        return json(
          page(
            state.incidents.filter(
              (i) =>
                !url.searchParams.has("status") ||
                i.status === url.searchParams.get("status"),
            ),
            url,
          ),
        );
      const i = {
        ...body,
        id: randomUUID(),
        organizationId: org,
        status: "OPEN",
        version: 0,
        createdAt: now,
      };
      state.incidents.unshift(i);
      return json(i, 201);
    }
    const incident = state.incidents.find((i) => i.id === id);
    if (!incident) return error(404);
    if (req.method === "GET") return json(incident);
    if (
      state.conflict ||
      Number(
        req.method === "DELETE"
          ? url.searchParams.get("version")
          : body.version,
      ) !== incident.version
    )
      return error(409);
    if (req.method === "DELETE") {
      state.incidents = state.incidents.filter((i) => i.id !== id);
      res.writeHead(204);
      return res.end();
    }
    Object.assign(incident, { ...body, version: incident.version + 1 });
    return json(incident);
  }
  if (area === "documents") {
    if (org === B) return id ? error(404) : json(page([], url, true));
    const doc = state.documents.find((d) => d.id === id);
    if (req.method === "POST") {
      const form = await new Response(raw, {
        headers: { "Content-Type": req.headers["content-type"] },
      }).formData();
      const file = form.get("file");
      if (!file || file.size === 0) return error(400);
      const created = id
        ? doc
        : {
            id: randomUUID(),
            organizationId: org,
            title: form.get("title"),
            createdBy: "fixture-user",
            status: "AVAILABLE",
            version: 0,
            createdAt: now,
            modifiedAt: now,
          };
      if (!created) return error(404);
      if (id && Number(form.get("version")) !== created.version)
        return error(409);
      const rev = {
        ...state.documents[0].currentRevision,
        id: randomUUID(),
        documentId: created.id,
        revisionNumber: (created.currentRevision?.revisionNumber ?? 0) + 1,
        originalFilename: file.name,
        fileSize: file.size,
      };
      created.currentRevision = rev;
      created.version++;
      created.status = "AVAILABLE";
      if (!id) state.documents.unshift(created);
      state.revisions.set(created.id, [
        rev,
        ...(state.revisions.get(created.id) ?? []),
      ]);
      return json(created, 201);
    }
    if (!id)
      return json(
        page(
          state.documents.filter(
            (d) => d.status === (url.searchParams.get("status") ?? "AVAILABLE"),
          ),
          url,
          true,
        ),
      );
    if (!doc || doc.status === "DELETED") return error(404);
    if (req.method === "PATCH") {
      if (state.conflict || body.version !== doc.version) return error(409);
      doc.title = body.title;
      doc.version++;
      return json(doc);
    }
    if (req.method === "DELETE") {
      if (
        state.conflict ||
        Number(url.searchParams.get("version")) !== doc.version
      )
        return error(409);
      doc.status = "DELETED";
      state.redacted = true;
      res.writeHead(204);
      return res.end();
    }
    if (url.pathname.endsWith("/ingestion"))
      return json({
        status: "VERIFIED",
        attempts: 1,
        errorCode: null,
        verifiedAt: now,
        processingMs: 120,
      });
    if (url.pathname.endsWith("/indexing"))
      return json({
        status: "INDEXED",
        phase: "INDEXING",
        attempts: 1,
        errorCode: null,
        processingMs: 220,
        publishedAt: now,
        configuration: "fixture",
        chunkCount: 3,
        published: true,
      });
    if (url.pathname.endsWith("/content")) {
      res.writeHead(200, {
        "Content-Type": "text/markdown",
        "Content-Disposition": 'attachment; filename="runbook.md"',
      });
      return res.end("# Recovery\nReduce pool to 20.");
    }
    if (parts[6] === "revisions")
      return json(page(state.revisions.get(id) ?? [], url, true));
    return json(doc);
  }
  if (area === "search") {
    const mode = url.searchParams.get("mode");
    return json({
      requestedMode: mode,
      effectiveMode: mode,
      degradationCode: null,
      returnedCount: org === B ? 0 : 1,
      results:
        org === B
          ? []
          : [
              {
                source: {
                  ...citation(),
                  ordinal: 0,
                  configuration: "fixture",
                  model: "fixture",
                  dimensions: 768,
                  score: 0.8,
                },
                keywordRank: 1,
                semanticRank: 1,
                score: 0.03,
              },
            ],
    });
  }
  if (area === "conversations") {
    if (org === B)
      return json({ items: [], totalElements: 0, page: 0, size: 20 });
    if (!id) {
      if (req.method === "GET") return json(page(state.conversations, url));
      const c = {
        id: randomUUID(),
        organizationId: org,
        title: body.title,
        createdAt: now,
        updatedAt: now,
      };
      state.conversations.unshift(c);
      return json(c, 201);
    }
    const convo = state.conversations.find((c) => c.id === id);
    if (!convo) return error(404);
    if (req.method === "DELETE") {
      state.conversations = state.conversations.filter((c) => c.id !== id);
      res.writeHead(204);
      return res.end();
    }
    if (req.method === "PATCH") {
      convo.title = body.title;
      return json(convo);
    }
    if (parts[6] === "messages" && req.method === "POST") {
      state.turnPosts.push(body.idempotencyKey);
      const existing = state.turns.get(body.idempotencyKey);
      if (existing) {
        if (existing.question !== body.content)
          return error(409, "IDEMPOTENCY_CONFLICT");
        complete(existing);
        return json(existing);
      }
      const turnId = randomUUID(),
        userId = randomUUID(),
        assistantId = randomUUID();
      const turn = {
        conversationId: id,
        userMessageId: userId,
        assistantMessageId: assistantId,
        status: "GENERATING",
        answer: null,
        citations: [],
        errorCode: null,
        createdAt: now,
        completedAt: null,
        question: body.content,
      };
      state.turns.set(body.idempotencyKey, turn);
      state.messages.push(
        {
          id: userId,
          turnId,
          sequence: state.messages.length + 1,
          role: "USER",
          content: body.content,
          status: "GENERATING",
          errorCode: null,
          citations: [],
          createdAt: now,
          completedAt: null,
        },
        {
          id: assistantId,
          turnId,
          sequence: state.messages.length + 2,
          role: "ASSISTANT",
          content: null,
          status: "GENERATING",
          errorCode: null,
          citations: [],
          createdAt: now,
          completedAt: null,
        },
      );
      if (state.turnTimeout) {
        state.turnTimeout = false;
        return error(503, "MODEL_TIMEOUT");
      }
      complete(turn);
      return json(turn);
    }
    if (parts[8] === "citations")
      return state.redacted ? error(404) : json(citation());
    const rows = state.messages.map((m) =>
      m.role === "ASSISTANT" && state.redacted
        ? { ...m, status: "REDACTED", content: "Unavailable", citations: [] }
        : m,
    );
    return json({
      conversation: convo,
      messages: rows.slice(
        Number(url.searchParams.get("page") ?? 0) * 20,
        Number(url.searchParams.get("page") ?? 0) * 20 + 20,
      ),
      totalElements: rows.length,
      page: Number(url.searchParams.get("page") ?? 0),
      size: 20,
    });
  }
  return error(404);
});
server.listen(18090, "127.0.0.1");
