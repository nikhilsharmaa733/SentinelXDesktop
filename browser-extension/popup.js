// Popup status: pings the app through the host and reports one of three honest
// states — connected, vault locked, or genuinely unreachable. The `locked`
// flag arrived with app 1.7; an older app omits it, which reads as false and
// still shows plain "Connected".
const api = typeof browser !== "undefined" ? browser : chrome;

api.runtime.sendMessage({ type: "hello" }).then((reply) => {
  const dot = document.getElementById("dot");
  const state = document.getElementById("state");
  const hint = document.getElementById("hint");
  if (reply && reply.type === "hello_ok" && reply.locked) {
    dot.className = "dot warn";
    state.textContent = "Vault is locked";
    hint.textContent = "Unlock SentinelX, then focus a login field to fill from your vault.";
  } else if (reply && reply.type === "hello_ok") {
    dot.className = "dot ok";
    state.textContent = `Connected — v${reply.version}`;
    hint.textContent = "Focus a login field to fill from your vault.";
  } else {
    dot.className = "dot off";
    state.textContent = "App not reachable";
    hint.textContent =
      "Open SentinelX and switch the Browser Bridge on from the Overview screen. " +
      "If it is already on, reinstall the bridge from that screen and reload this extension.";
  }
}).catch(() => {
  document.getElementById("state").textContent = "App not reachable";
});
