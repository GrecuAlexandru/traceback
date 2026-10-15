import { test, expect, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { mkdir } from "node:fs/promises";
const A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
  B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
  I = "11111111-1111-4111-8111-111111111111",
  D = "22222222-2222-4222-8222-222222222222",
  C = "44444444-4444-4444-8444-444444444444";
const fixture = "http://127.0.0.1:18090";
async function login(page: Page) {
  await page.goto("/login");
  await page.getByRole("link", { name: "Continue with Keycloak" }).click();
  await expect(page).toHaveURL(new RegExp(`${A}/overview`));
  await expect(
    page.getByRole("heading", { name: "Overview", exact: true }),
  ).toBeVisible();
}
async function area(page: Page, screen: string) {
  await page.goto(`/${A}/${screen}`);
  await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
}
async function control(page: Page, data: Record<string, unknown>) {
  await page.request.post(fixture + "/__control", { data });
}
test.beforeEach(async ({ request }) => {
  await request.post(fixture + "/__reset");
});
test("protected routes, PKCE sign-in, server session and logout", async ({
  page,
  context,
}) => {
  await page.goto(`/${A}/incidents`);
  await expect(page).toHaveURL(/\/login/);
  await login(page);
  const cookies = await context.cookies();
  const session = cookies.find((c) => c.name === "traceback_session");
  expect(session?.httpOnly).toBe(true);
  expect(session?.sameSite).toBe("Lax");
  expect(await page.evaluate(() => Object.keys(localStorage))).toEqual([]);
  await page.getByRole("button", { name: "Fixture engineer" }).click();
  await page.getByRole("menuitem", { name: "Sign out" }).click();
  await expect(page).toHaveURL(/\/login$/);
  await page.goto(`/${A}/incidents`);
  await expect(page).toHaveURL(/\/login/);
});
test("CSRF, path allowlist and failed callback are rejected", async ({
  page,
}) => {
  await login(page);
  expect(
    (
      await page.request.post("/api/backend/organizations", {
        data: { name: "unexpected" },
      })
    ).status(),
  ).toBe(403);
  expect(
    (await page.request.get("/api/backend/actuator/health")).status(),
  ).toBe(404);
  await page.goto("/callback?code=forged&state=forged");
  await expect(page).toHaveURL(/authentication_failed/);
});
test("session expiry removes the workspace", async ({ page }) => {
  await login(page);
  await control(page, { unauthorized: true });
  await area(page, "incidents");
  await expect(page).toHaveURL(/session_expired/);
  await expect(page.getByText("Connection pool exhaustion")).toHaveCount(0);
});
test("organization switching clears prior incident, search and replay state", async ({
  page,
}) => {
  await login(page);
  await area(page, "search");
  await page
    .getByRole("textbox", { name: "Search engineering knowledge" })
    .fill("pool timeout");
  await page.getByRole("button", { name: "Search", exact: true }).click();
  await expect(
    page.getByRole("link", { name: "Database recovery runbook" }),
  ).toBeVisible();
  await page.evaluate(() =>
    sessionStorage.setItem(
      "traceback:turn:v1:fixture",
      "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
    ),
  );
  await page.getByRole("combobox", { name: "Organization" }).click();
  await page
    .getByRole("option", { name: "Observability", exact: true })
    .click();
  await expect(page).toHaveURL(new RegExp(`${B}/overview`));
  await expect(page.getByText("Database recovery runbook")).toHaveCount(0);
  expect(await page.evaluate(() => Object.keys(sessionStorage))).toEqual([]);
  await page.getByRole("link", { name: "Incidents", exact: true }).click();
  await expect(
    page.getByRole("link", { name: "Observability tenant incident" }),
  ).toBeVisible();
  await expect(page.getByText("Connection pool exhaustion")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "New incident" })).toHaveCount(
    0,
  );
});
test("incident CRUD, validation and expected versions", async ({ page }) => {
  await login(page);
  await area(page, "incidents");
  await page.getByRole("button", { name: "New incident" }).click();
  await page.getByRole("button", { name: "Create incident" }).click();
  await expect(
    page.getByText("Enter a title of 1–200 characters."),
  ).toBeVisible();
  await page
    .getByRole("textbox", { name: /Title/ })
    .fill("Rollback investigation");
  await page
    .getByRole("textbox", { name: "Description" })
    .fill("Synthetic test incident");
  await page.getByRole("button", { name: "Create incident" }).click();
  await expect(
    page.getByRole("heading", { name: "Rollback investigation", level: 1 }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Edit incident" }).click();
  await page.getByRole("textbox", { name: /Title/ }).fill("Rollback resolved");
  await page.getByRole("combobox", { name: "Status", exact: true }).click();
  await page.getByRole("option", { name: "resolved", exact: true }).click();
  await page.getByRole("button", { name: "Save changes" }).click();
  await expect(
    page.getByRole("heading", { name: "Rollback resolved", level: 1 }),
  ).toBeVisible();
  await expect(page.getByText("Version 1", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Delete incident" }).click();
  await page.getByRole("button", { name: "Confirm deletion" }).click();
  await expect(page).toHaveURL(new RegExp(`${A}/incidents$`));
  await expect(page.getByText("Rollback resolved")).toHaveCount(0);
});
test("conflicts require explicit reload and viewers cannot edit", async ({
  page,
}) => {
  await login(page);
  await area(page, `incidents/${I}`);
  await page.getByRole("button", { name: "Edit incident" }).click();
  await control(page, { conflict: true });
  await page.getByRole("button", { name: "Save changes" }).click();
  await expect(
    page.getByRole("button", { name: "Reload latest" }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Save changes" }),
  ).toBeDisabled();
  await page.getByRole("button", { name: "Reload latest" }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await control(page, { role: "VIEWER", conflict: false });
  await page.reload();
  await expect(page.getByText("Read-only membership")).toBeVisible();
  await expect(page.getByRole("button", { name: "Edit incident" })).toHaveCount(
    0,
  );
  await area(page, "documents");
  await expect(
    page.getByRole("button", { name: "Upload document" }),
  ).toHaveCount(0);
});
test("document upload, revision, rename, download and deletion", async ({
  page,
}) => {
  await login(page);
  await area(page, "documents");
  await page.getByRole("button", { name: "Upload document" }).click();
  await page
    .getByRole("textbox", { name: "Document title" })
    .fill("Synthetic postmortem");
  await page.locator("input[type=file]").setInputFiles({
    name: "postmortem.md",
    mimeType: "text/markdown",
    buffer: Buffer.from("# Postmortem\nRecovered safely."),
  });
  await page
    .getByRole("button", { name: "Upload document", exact: true })
    .last()
    .click();
  await expect(
    page.getByRole("heading", { name: "Synthetic postmortem", level: 1 }),
  ).toBeVisible();
  await expect(page.getByText("verified", { exact: true })).toBeVisible();
  await expect(page.getByText("indexed", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "New revision" }).click();
  await page.locator("input[type=file]").setInputFiles({
    name: "postmortem-v2.txt",
    mimeType: "text/plain",
    buffer: Buffer.from("Second revision"),
  });
  await page
    .getByRole("button", { name: "Upload revision", exact: true })
    .click();
  await expect(
    page.getByText("postmortem-v2.txt", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Download revision 1" }),
  ).toBeVisible();
  const download = page.waitForEvent("download");
  await page.getByRole("button", { name: "Download source" }).click();
  expect((await download).suggestedFilename()).toBe("postmortem-v2.txt");
  await page.getByRole("button", { name: "Rename", exact: true }).click();
  await page
    .getByRole("textbox", { name: "Document title" })
    .fill("Revised postmortem");
  await page.getByRole("button", { name: "Save title" }).click();
  await expect(
    page.getByRole("heading", { name: "Revised postmortem", level: 1 }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Delete document" }).click();
  await expect(
    page.getByText(/Original content is retained privately/),
  ).toBeVisible();
  await page.getByRole("button", { name: "Confirm deletion" }).click();
  await expect(page).toHaveURL(new RegExp(`${A}/documents$`));
  await expect(page.getByText("Revised postmortem")).toHaveCount(0);
});
test("search modes, provider errors and results", async ({ page }) => {
  await login(page);
  await area(page, "search");
  for (const mode of ["Keyword", "Semantic", "Hybrid"]) {
    await page.getByRole("combobox", { name: "Search mode" }).click();
    await page.getByRole("option", { name: mode, exact: true }).click();
    await page
      .getByRole("textbox", { name: "Search engineering knowledge" })
      .fill("HikariPool-1");
    await page.getByRole("button", { name: "Search", exact: true }).click();
    await expect(
      page.getByText(`${mode.toLowerCase()} results`, { exact: true }),
    ).toBeVisible();
    await expect(
      page.getByText(/reduce the pool to 20 and close leaked connections/),
    ).toBeVisible();
  }
  await control(page, { unavailable: true });
  await page.getByRole("button", { name: "Search", exact: true }).click();
  await expect(page.getByRole("alert")).toBeVisible();
});
test("conversation creation, history, timeout replay, citations and redaction", async ({
  page,
}) => {
  await login(page);
  await area(page, "assistant");
  await page.getByRole("button", { name: "New conversation" }).click();
  await page
    .getByRole("textbox", { name: "Conversation title" })
    .fill("Pool recovery");
  await page.getByRole("button", { name: "Create conversation" }).click();
  await expect(
    page.getByRole("heading", { name: "Pool recovery" }),
  ).toBeVisible();
  await control(page, { turnTimeout: true });
  await page
    .getByRole("textbox", { name: "Message", exact: true })
    .fill("How do we recover the pool?");
  await page.getByRole("button", { name: "Send message" }).click();
  await expect(
    page.getByRole("button", { name: "Check saved turn" }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Send message" }),
  ).toBeDisabled();
  await page.reload();
  await expect(
    page.getByRole("button", { name: "Check saved turn" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Check saved turn" }).click();
  await expect(
    page.getByRole("button", { name: "S1 · Database recovery runbook" }),
  ).toBeVisible();
  const state = await (await page.request.get(fixture + "/__state")).json();
  expect(state.questions).toBe(1);
  expect(state.turnPosts).toHaveLength(2);
  expect(state.turnPosts[0]).toBe(state.turnPosts[1]);
  await page
    .getByRole("button", { name: "S1 · Database recovery runbook" })
    .click();
  await expect(page.getByRole("dialog", { name: "Source S1" })).toBeVisible();
  await expect(
    page.getByRole("link", { name: "Open source document" }),
  ).toHaveAttribute("href", `/${A}/documents/${D}`);
  await page.getByRole("button", { name: "Close", exact: true }).click();
  await control(page, { redacted: true });
  await page.getByRole("button", { name: "Refresh history" }).click();
  await expect(page.getByText(/This answer is unavailable/)).toBeVisible();
  await expect(
    page.getByRole("button", { name: "S1 · Database recovery runbook" }),
  ).toHaveCount(0);
});
test("keyboard dialogs, mobile navigation and overflow", async ({ page }) => {
  await login(page);
  await area(page, "incidents");
  await page.getByRole("button", { name: "New incident" }).focus();
  await page.keyboard.press("Enter");
  await expect(
    page.getByRole("dialog", { name: "New incident" }),
  ).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "New incident" }),
  ).toBeFocused();
  await page.setViewportSize({ width: 390, height: 844 });
  await page.reload();
  await page.getByRole("button", { name: /navigation/i }).click();
  await expect(
    page.getByRole("link", { name: "AI Assistant", exact: true }),
  ).toBeVisible();
  await page.getByRole("link", { name: "AI Assistant", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "AI Assistant", level: 1 }),
  ).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
});
test("major screens, light/dark and mobile visual evidence", async ({
  page,
}) => {
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  page.on("console", (message) => {
    if (["error", "warn"].includes(message.type())) errors.push(message.text());
  });
  const output = "/tmp/traceback-m16-qa";
  await mkdir(output, { recursive: true });
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/login");
  await page.screenshot({ path: output + "/login-light.png" });
  await login(page);
  for (const [screen, file, wait] of [
    ["overview", "overview-light", "Connection pool exhaustion"],
    ["incidents", "incidents-light", "Connection pool exhaustion"],
    [`incidents/${I}`, "incident-detail-light", "Investigation"],
    ["documents", "documents-light", "Database recovery runbook"],
    [`documents/${D}`, "document-detail-light", "Revision history"],
    ["settings", "settings-light", "Color theme"],
  ]) {
    await area(page, screen);
    await expect(page.getByText(wait, { exact: true }).first()).toBeVisible();
    await page.screenshot({ path: output + `/${file}.png` });
  }
  await page.getByRole("combobox", { name: "Color theme" }).click();
  await page.getByRole("option", { name: "Dark", exact: true }).click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await area(page, "incidents");
  await expect(
    page.getByRole("link", { name: "Connection pool exhaustion" }),
  ).toBeVisible();
  const frame = await page.locator(".table-panel").boundingBox();
  const header = await page
    .getByRole("columnheader", { name: "Incident", exact: true })
    .boundingBox();
  expect(header!.y).toBeGreaterThanOrEqual(frame!.y);
  await page.screenshot({ path: output + "/incidents-dark.png" });
  await area(page, "search");
  await page
    .getByRole("textbox", { name: "Search engineering knowledge" })
    .fill("pool timeout");
  await page.getByRole("button", { name: "Search", exact: true }).click();
  await expect(
    page.getByRole("link", { name: "Database recovery runbook" }),
  ).toBeVisible();
  await page.screenshot({ path: output + "/search-dark.png" });
  await area(page, `assistant/${C}`);
  await page
    .getByRole("textbox", { name: "Message", exact: true })
    .fill("What fixes the pool timeout?");
  await page.getByRole("button", { name: "Send message" }).click();
  await expect(
    page.getByRole("button", { name: "S1 · Database recovery runbook" }),
  ).toBeVisible();
  await page.screenshot({ path: output + "/assistant-dark.png" });
  await page.setViewportSize({ width: 390, height: 844 });
  await area(page, "incidents");
  await expect(
    page.getByRole("link", { name: "Connection pool exhaustion" }),
  ).toBeVisible();
  await page.screenshot({ path: output + "/incidents-mobile.png" });
  await page.getByRole("button", { name: /navigation/i }).click();
  await expect(
    page.getByRole("link", { name: "AI Assistant", exact: true }),
  ).toBeInViewport({ ratio: 1 });
  await page.screenshot({ path: output + "/mobile-navigation.png" });
  expect(errors).toEqual([]);
});

test("accessible landmarks, labels and contrast in both themes", async ({
  page,
}) => {
  await login(page);
  await page.emulateMedia({ reducedMotion: "reduce" });
  for (const screen of [
    "overview",
    "incidents",
    `documents/${D}`,
    "search",
    `assistant/${C}`,
    "settings",
  ]) {
    await area(page, screen);
    await expect(page.getByRole("main")).toBeVisible();
    if (screen.startsWith("assistant"))
      await expect(
        page.getByRole("button", { name: "Rename", exact: true }),
      ).toBeEnabled();
    if (screen.startsWith("assistant"))
      await expect(
        page.getByRole("button", { name: "Rename", exact: true }),
      ).toHaveCSS("opacity", "1");
    const result = await new AxeBuilder({ page })
      .withTags(["wcag2a", "wcag2aa", "wcag21aa"])
      .analyze();
    expect(
      result.violations.map((v) => ({
        id: v.id,
        nodes: v.nodes.map((n) => ({
          target: n.target,
          summary: n.failureSummary,
        })),
      })),
    ).toEqual([]);
  }
  await page.getByRole("combobox", { name: "Color theme" }).click();
  await page.getByRole("option", { name: "Dark", exact: true }).click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await expect(page.getByRole("combobox", { name: "Color theme" })).toHaveCSS(
    "color",
    "rgb(255, 255, 255)",
  );
  const dark = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21aa"])
    .analyze();
  expect(dark.violations).toEqual([]);
});

test("Markdown notes and conversation continuation, rename and deletion", async ({
  page,
}) => {
  await login(page);
  await area(page, "documents");
  await page.getByRole("button", { name: "Upload document" }).click();
  await page
    .getByRole("textbox", { name: /Document title/ })
    .fill("Pasted runbook");
  await page.getByRole("combobox", { name: "Source input" }).click();
  await page.getByRole("option", { name: "Paste Markdown notes" }).click();
  await page
    .getByRole("textbox", { name: /Markdown notes/ })
    .fill("# Recovery\nReduce the pool to 20.");
  await page
    .getByRole("dialog", { name: "Upload document" })
    .getByRole("button", { name: "Upload document", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Pasted runbook", level: 1 }),
  ).toBeVisible();
  await expect(
    page.getByText("engineering-notes.md", { exact: true }),
  ).toBeVisible();
  await area(page, `assistant/${C}`);
  for (const text of ["How do I fix the pool?", "What should I check next?"]) {
    await page
      .getByRole("textbox", { name: "Message", exact: true })
      .fill(text);
    await page.getByRole("button", { name: "Send message" }).click();
    await expect(
      page.getByRole("button", { name: "Send message" }),
    ).not.toHaveAttribute("aria-busy", "true");
    await expect(
      page.getByRole("textbox", { name: "Message", exact: true }),
    ).toHaveValue("");
  }
  expect(
    (await (await page.request.get(fixture + "/__state")).json()).questions,
  ).toBe(2);
  await page.getByRole("button", { name: "Rename", exact: true }).click();
  await page
    .getByRole("textbox", { name: /Conversation title/ })
    .fill("Completed investigation");
  await page.getByRole("button", { name: "Save title" }).click();
  await expect(
    page.getByRole("heading", { name: "Completed investigation" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Delete conversation" }).click();
  await page.getByRole("button", { name: "Confirm deletion" }).click();
  await expect(page).toHaveURL(new RegExp(`${A}/assistant$`));
  await expect(
    page.getByRole("link", { name: "Completed investigation" }),
  ).toHaveCount(0);
});

test("delete conflicts reload inside confirmation, and inaccessible sources hide excerpts", async ({
  page,
}) => {
  await login(page);
  await area(page, `incidents/${I}`);
  await page.getByRole("button", { name: "Delete incident" }).click();
  await control(page, { conflict: true });
  await page.getByRole("button", { name: "Confirm deletion" }).click();
  await expect(
    page.getByRole("dialog").getByRole("button", { name: "Confirm deletion" }),
  ).toBeDisabled();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Reload latest" })
    .click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await control(page, { conflict: false });
  await area(page, `assistant/${C}`);
  await page
    .getByRole("textbox", { name: "Message", exact: true })
    .fill("What fixes the timeout?");
  await page.getByRole("button", { name: "Send message" }).click();
  await expect(
    page.getByRole("button", { name: "S1 · Database recovery runbook" }),
  ).toBeVisible();
  await control(page, { redacted: true });
  await page
    .getByRole("button", { name: "S1 · Database recovery runbook" })
    .click();
  await expect(page.getByRole("dialog").getByRole("alert")).toBeVisible();
  await expect(page.getByRole("dialog").locator("blockquote")).toHaveCount(0);
  await expect(
    page
      .getByRole("dialog")
      .getByRole("link", { name: "Open source document" }),
  ).toHaveCount(0);
  await page.getByRole("button", { name: "Close", exact: true }).click();
  await expect(page.getByText(/This answer is unavailable/)).toBeVisible();
});
