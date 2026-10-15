export async function register() {
  if (process.env.NEXT_RUNTIME === "nodejs") {
    const { configuration } = await import("./lib/server/config");
    // Explicit deployment mode validates before readiness; fixture/local starts retain
    // their existing loopback-only setup. No network discovery or tokens at startup.
    if (process.env.TRACEBACK_ENVIRONMENT) configuration();
  }
}
