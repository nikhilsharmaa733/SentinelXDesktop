// SentinelX Bridge — background service worker.
//
// Owns the single native-messaging connection to the local host process and
// correlates request/response by reqId. Content scripts (one per tab) send it
// query/fill/capture messages; it forwards them to the host and routes each
// reply back to the tab that asked. It holds no secrets between messages — a
// fill's password is handed straight to the requesting content script and never
// cached here.

const api = typeof browser !== "undefined" ? browser : chrome;
const HOST_NAME = "com.nikhil.sentinelx.bridge";

let port = null;
const pending = new Map(); // reqId -> { resolve, tabId }
let counter = 0;

function nextReqId() {
  counter = (counter + 1) % Number.MAX_SAFE_INTEGER;
  return `${Date.now()}-${counter}`;
}

function connectHost() {
  if (port) return port;
  try {
    port = api.runtime.connectNative(HOST_NAME);
  } catch (e) {
    port = null;
    return null;
  }
  port.onMessage.addListener((msg) => {
    const entry = pending.get(msg.reqId);
    if (entry) {
      pending.delete(msg.reqId);
      entry.resolve(msg);
    }
  });
  port.onDisconnect.addListener(() => {
    // Fail every outstanding request so no content script hangs.
    for (const [, entry] of pending) entry.resolve({ type: "error", reason: "disconnected" });
    pending.clear();
    port = null;
  });
  return port;
}

function sendToHost(payload) {
  return new Promise((resolve) => {
    const p = connectHost();
    if (!p) return resolve({ type: "error", reason: "unavailable" });
    const reqId = nextReqId();
    payload.reqId = reqId;
    pending.set(reqId, { resolve });
    try {
      p.postMessage(payload);
    } catch (e) {
      pending.delete(reqId);
      resolve({ type: "error", reason: "unavailable" });
    }
    // Fill/capture block on a human approving a dialog, so allow a long window.
    const timeout = payload.type === "query" ? 5000 : 120000;
    setTimeout(() => {
      if (pending.has(reqId)) {
        pending.delete(reqId);
        resolve({ type: "error", reason: "timeout" });
      }
    }, timeout);
  });
}

api.runtime.onMessage.addListener((message, sender, sendResponse) => {
  // Everything a content script asks maps 1:1 to a host request.
  sendToHost(message).then(sendResponse);
  return true; // async response
});
