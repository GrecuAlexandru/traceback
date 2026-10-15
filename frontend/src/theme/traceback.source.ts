import { defineTheme } from "@astryxdesign/core/theme";
import { neutralTheme } from "@astryxdesign/theme-neutral/built";
export const tracebackTheme = defineTheme({
  name: "traceback",
  extends: neutralTheme,
  tokens: {
    "--color-accent": ["#23623e", "#94d8a2"],
    "--color-background-body": ["#f7f8f9", "#121416"],
    "--color-background-surface": ["#ffffff", "#181a1d"],
    "--color-background-card": ["#ffffff", "#181a1d"],
    "--color-background-muted": ["#edf0f2", "#202428"],
    "--color-border": ["#d6dce0", "#30363c"],
    "--font-family-body": "Arial, Helvetica, sans-serif",
  },
  components: {
    button: {
      "variant:primary": {
        backgroundColor: "var(--color-accent)",
        color: "light-dark(#ffffff, #102318)",
      },
    },
    "side-nav": { base: { width: "240px" } },
  },
});
