"use client";
import {
  createContext,
  useContext,
  useSyncExternalStore,
  type ReactNode,
} from "react";
import Link from "next/link";
import { Theme } from "@astryxdesign/core/theme";
import { LinkProvider } from "@astryxdesign/core/Link";
import { tracebackTheme } from "@/theme/traceback";
type Mode = "light" | "dark" | "system";
const Appearance = createContext<{ mode: Mode; setMode: (mode: Mode) => void }>(
  { mode: "system", setMode: () => {} },
);
export const useAppearance = () => useContext(Appearance);
const subscribe = (listener: () => void) => {
  window.addEventListener("storage", listener);
  window.addEventListener("traceback:appearance", listener);
  return () => {
    window.removeEventListener("storage", listener);
    window.removeEventListener("traceback:appearance", listener);
  };
};
function snapshot(): Mode {
  const stored = localStorage.getItem("traceback:appearance:v1");
  return stored === "dark" || stored === "light" ? stored : "system";
}
export function Providers({ children }: { children: ReactNode }) {
  const mode = useSyncExternalStore<Mode>(subscribe, snapshot, () => "system");
  function change(mode: Mode) {
    localStorage.setItem("traceback:appearance:v1", mode);
    window.dispatchEvent(new Event("traceback:appearance"));
  }
  return (
    <Appearance.Provider value={{ mode, setMode: change }}>
      <Theme theme={tracebackTheme} mode={mode}>
        <LinkProvider component={Link}>{children}</LinkProvider>
      </Theme>
    </Appearance.Provider>
  );
}
