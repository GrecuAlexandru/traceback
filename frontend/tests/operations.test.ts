import { expect, it, vi } from "vitest";
import { operationalFailure } from "@/lib/server/operations";
it("emits only fixed operational labels", () => {
  const log = vi.spyOn(console, "warn").mockImplementation(() => {});
  try {
    for (const boundary of ["bff", "callback", "refresh"] as const) {
      operationalFailure(boundary);
      expect(JSON.parse(log.mock.calls.at(-1)![0])).toEqual({
        event: "traceback.frontend.failure",
        boundary,
      });
    }
  } finally {
    log.mockRestore();
  }
});
