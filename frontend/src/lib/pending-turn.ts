// A tab-local replay UUID only; never persist question, answer, excerpt or token.
const prefix = "traceback:turn:v1:";
export const replayKey = (
  identity: string,
  org: string,
  conversation: string,
) => `${prefix}${identity}:${org}:${conversation}`;
export function readReplay(key: string) {
  const value = sessionStorage.getItem(key);
  return value && /^[0-9a-f-]{36}$/i.test(value) ? value : null;
}
export function saveReplay(key: string, id: string) {
  sessionStorage.setItem(key, id);
  window.dispatchEvent(new Event("traceback:replay"));
}
export function clearReplays() {
  for (const key of Object.keys(sessionStorage))
    if (key.startsWith(prefix)) sessionStorage.removeItem(key);
}
export function removeReplay(key: string) {
  sessionStorage.removeItem(key);
  window.dispatchEvent(new Event("traceback:replay"));
}
export function subscribeReplay(listener: () => void) {
  window.addEventListener("traceback:replay", listener);
  return () => window.removeEventListener("traceback:replay", listener);
}
