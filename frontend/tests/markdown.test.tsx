import { render, screen, cleanup } from "@testing-library/react";
import { describe, it, expect, afterEach } from "vitest";
import { SafeMarkdown, safeHref } from "@/components/safe-markdown";
afterEach(cleanup);
describe("Untrusted answer rendering", () => {
  it("drops raw HTML, scripts, event handlers and tracking images", () => {
    const { container } = render(
      <SafeMarkdown
        content={
          '<script>alert(1)</script>\n\n<img src="https://evil.test/track" onerror="alert(1)">\n\n![tracker](https://evil.test/pixel)\n\nSafe **answer**'
        }
      />,
    );
    expect(container.querySelector("script,img")).toBeNull();
    expect(screen.getByText("answer")).toBeInTheDocument();
  });
  it("blocks executable, protocol-relative, internal and data links", () => {
    for (const url of [
      "javascript:alert(1)",
      "data:text/html,x",
      "//evil.test",
      "/api/auth/login",
      "/api/backend/me",
    ])
      expect(safeHref(url)).toBeUndefined();
  });
  it("renders code, GFM tables and safe external links", () => {
    const { container } = render(
      <SafeMarkdown
        content={
          '```java\nSystem.out.println("safe");\n```\n\n| Name | Value |\n| --- | --- |\n| pool | 20 |\n\n[Reference](https://example.org)'
        }
      />,
    );
    expect(container.querySelector("pre code")).toHaveTextContent(
      "System.out.println",
    );
    expect(container.querySelector("table")).toBeInTheDocument();
    expect(screen.getByRole("link")).toHaveAttribute(
      "rel",
      "noopener noreferrer",
    );
  });
});
