import "server-only";

// Fixed fields only: never accept an Error, URL, status text, user ID or request body.
export function operationalFailure(boundary: "bff" | "callback" | "refresh") {
  console.warn(
    JSON.stringify({ event: "traceback.frontend.failure", boundary }),
  );
}
