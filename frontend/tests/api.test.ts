import { describe, it, expect, beforeEach, vi, afterEach } from "vitest";
import { api, writes, keys, ApiError, validFile, byteLength } from "@/lib/api";
import {
  saveReplay,
  readReplay,
  clearReplays,
  replayKey,
} from "@/lib/pending-turn";
beforeEach(() => {
  vi.stubGlobal(
    "fetch",
    vi.fn().mockImplementation(
      () =>
        new Response("{}", {
          status: 200,
          headers: { "content-type": "application/json" },
        }),
    ),
  );
});
afterEach(() => vi.unstubAllGlobals());
describe("Typed writes and tenant state", () => {
  it("sends the version on both optimistic-lock operations", async () => {
    await writes.updateIncident("org", "item", {
      title: "t",
      description: "d",
      status: "OPEN",
      version: 7,
    });
    expect(
      JSON.parse(vi.mocked(fetch).mock.calls[0][1]!.body as string).version,
    ).toBe(7);
    await writes.deleteIncident("org", "item", 7);
    expect(vi.mocked(fetch).mock.calls[1][0]).toContain("?version=7");
  });
  it("keeps supplied chat idempotency keys and never retries a failed write", async () => {
    vi.mocked(fetch).mockImplementation(
      async () => new Response('{"code":"MODEL_TIMEOUT"}', { status: 503 }),
    );
    await expect(
      writes.send("org", "conversation", "fixed-key", "question"),
    ).rejects.toBeInstanceOf(ApiError);
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(
      JSON.parse(vi.mocked(fetch).mock.calls[0][1]!.body as string)
        .idempotencyKey,
    ).toBe("fixed-key");
  });
  it("includes tenant in all resource keys", () => {
    expect(keys.history("a", "same")).not.toEqual(keys.history("b", "same"));
    expect(keys.documents("a")).not.toEqual(keys.documents("b"));
  });
  it("retains only replay UUIDs and clears every tenant on switch", () => {
    const key = replayKey("user", "org", "conversation");
    const id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    saveReplay(key, id);
    expect(readReplay(key)).toBe(id);
    expect(sessionStorage.getItem(key)).toBe(id);
    clearReplays();
    expect(readReplay(key)).toBeNull();
  });
  it("announces 401 without logging or returning tokens", async () => {
    const listener = vi.fn();
    window.addEventListener("traceback:expired", listener);
    vi.mocked(fetch).mockResolvedValue(new Response("{}", { status: 401 }));
    await expect(api("me")).rejects.toMatchObject({ status: 401 });
    expect(listener).toHaveBeenCalled();
    window.removeEventListener("traceback:expired", listener);
  });
  it("validates type, measured file size and UTF-8 byte budget", () => {
    expect(validFile(new File(["x"], "runbook.md"), 10)).toBe(true);
    expect(validFile(new File([""], "runbook.txt"), 10)).toBe(false);
    expect(validFile(new File(["x"], "attack.html"), 10)).toBe(false);
    expect(validFile(new File(["x".repeat(11)], "runbook.pdf"), 10)).toBe(
      false,
    );
    expect(byteLength("😀")).toBe(4);
  });
});
