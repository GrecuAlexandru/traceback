import process from "node:process";
import console from "node:console";
import { configuration } from "../src/lib/server/runtime-config.mjs";
// Next's instrumentation failure leaves its listener alive. Validate before importing
// standalone server.js so bad production configuration exits before opening a port.
try {
  configuration();
  await import("../server.js");
} catch {
  console.error("Invalid or incomplete frontend deployment configuration");
  process.exit(1);
}
