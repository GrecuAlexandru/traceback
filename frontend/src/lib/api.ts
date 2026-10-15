import type {
  Document,
  Incident,
  IncidentStatus,
  Organization,
  Conversation,
  Turn,
} from "./contracts";
export class ApiError extends Error {
  constructor(
    public status: number,
    public code = "REQUEST_FAILED",
  ) {
    super(code);
  }
}
export const messages: Record<number, string> = {
  400: "Check the supplied values and limits.",
  401: "Your session has expired. Sign in again to continue.",
  403: "Your current membership does not permit this action.",
  404: "This resource is unavailable or you no longer have access.",
  409: "The resource changed. Reload the latest version before trying again.",
  413: "The upload exceeds the allowed size.",
  415: "This file or request format is unsupported.",
  429: "Generation capacity is busy. Wait, then check the turn status.",
  503: "A required service is unavailable. Check the saved state before retrying a write.",
};
export function errorText(error: unknown) {
  return error instanceof ApiError
    ? (messages[error.status] ?? "The request failed. Please try again.")
    : "Unable to reach Traceback. Check your connection.";
}
export async function api<T>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const headers = new Headers(options.headers);
  headers.set("X-Traceback-Request", "1");
  if (options.body && !(options.body instanceof FormData))
    headers.set("Content-Type", "application/json");
  const response = await fetch("/api/backend/" + path, {
    ...options,
    headers,
    cache: "no-store",
    credentials: "same-origin",
  });
  if (!response.ok) {
    let code;
    try {
      code = (await response.json()).code;
    } catch {
      /* Error bodies are optional. */
    }
    if (response.status === 401)
      window.dispatchEvent(new Event("traceback:expired"));
    throw new ApiError(response.status, code);
  }
  return response.status === 204
    ? (undefined as T)
    : ((await response.json()) as T);
}
export const tenant = (org: string) =>
  `organizations/${encodeURIComponent(org)}`;
export const keys = {
  incidents: (org: string) => [org, "incidents"] as const,
  documents: (org: string) => [org, "documents"] as const,
  conversations: (org: string) => [org, "conversations"] as const,
  history: (org: string, id: string) => [org, "history", id] as const,
};
export const writes = {
  createIncident: (org: string, data: { title: string; description: string }) =>
    api<Incident>(`${tenant(org)}/incidents`, {
      method: "POST",
      body: JSON.stringify(data),
    }),
  updateIncident: (
    org: string,
    id: string,
    data: {
      title: string;
      description: string;
      status: IncidentStatus;
      version: number;
    },
  ) =>
    api<Incident>(`${tenant(org)}/incidents/${id}`, {
      method: "PATCH",
      body: JSON.stringify(data),
    }),
  deleteIncident: (org: string, id: string, version: number) =>
    api<void>(`${tenant(org)}/incidents/${id}?version=${version}`, {
      method: "DELETE",
    }),
  renameDocument: (org: string, id: string, title: string, version: number) =>
    api<Document>(`${tenant(org)}/documents/${id}`, {
      method: "PATCH",
      body: JSON.stringify({ title, version }),
    }),
  uploadDocument: (org: string, title: string, file: File) => {
    const form = new FormData();
    form.set("title", title);
    form.set("file", file);
    return api<Document>(`${tenant(org)}/documents`, {
      method: "POST",
      body: form,
    });
  },
  reviseDocument: (org: string, id: string, version: number, file: File) => {
    const form = new FormData();
    form.set("version", String(version));
    form.set("file", file);
    return api<Document>(`${tenant(org)}/documents/${id}/revisions`, {
      method: "POST",
      body: form,
    });
  },
  deleteDocument: (org: string, id: string, version: number) =>
    api<void>(`${tenant(org)}/documents/${id}?version=${version}`, {
      method: "DELETE",
    }),
  createOrganization: (name: string) =>
    api<Organization>("organizations", {
      method: "POST",
      body: JSON.stringify({ name }),
    }),
  createConversation: (org: string, title: string) =>
    api<Conversation>(`${tenant(org)}/conversations`, {
      method: "POST",
      body: JSON.stringify({ title }),
    }),
  renameConversation: (org: string, id: string, title: string) =>
    api<Conversation>(`${tenant(org)}/conversations/${id}`, {
      method: "PATCH",
      body: JSON.stringify({ title }),
    }),
  deleteConversation: (org: string, id: string) =>
    api<void>(`${tenant(org)}/conversations/${id}`, { method: "DELETE" }),
  send: (org: string, id: string, idempotencyKey: string, content: string) =>
    api<Turn>(`${tenant(org)}/conversations/${id}/messages`, {
      method: "POST",
      body: JSON.stringify({ idempotencyKey, content }),
    }),
};
export function validFile(file: File | null, max: number) {
  return (
    !!file &&
    file.size > 0 &&
    file.size <= max &&
    /\.(pdf|md|markdown|txt)$/i.test(file.name)
  );
}
export function byteLength(text: string) {
  return new TextEncoder().encode(text).length;
}
export async function download(
  org: string,
  doc: string,
  revision: string | undefined,
  filename: string,
) {
  const response = await fetch(
    `/api/backend/${tenant(org)}/documents/${doc}/${revision ? `revisions/${revision}/` : ""}content`,
    { cache: "no-store" },
  );
  if (!response.ok) {
    if (response.status === 401)
      window.dispatchEvent(new Event("traceback:expired"));
    throw new ApiError(response.status);
  }
  const url = URL.createObjectURL(await response.blob());
  const a = document.createElement("a");
  a.href = url;
  a.download = filename.replace(/[/\\\u0000-\u001f]/g, "_");
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
