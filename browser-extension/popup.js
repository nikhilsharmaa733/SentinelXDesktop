// Popup status: pings the app through the host and reports whether the vault is
// reachable and unlocked.
const api = typeof browser !== "undefined" ? browser : chrome;

api.runtime.sendMessage({ type: "hello" }).then((reply) => {
  const dot = document.getElementById("dot");
  const state = document.getElementById("state");
  const hint = document.getElementById("hint");
  if (reply && reply.type === "hello_ok") {
    dot.className = "dot ok";
    state.textContent = `Connected — v${reply.version}`;
    hint.textContent = "Focus a login field to fill from your vault.";
  } else {
    dot.className = "dot off";
    state.textContent = "App not reachable";
    hint.textContent =
      "Open SentinelX and switch the Browser Bridge on from the Overview screen.";
  }
}).catch(() => {
  document.getElementById("state").textContent = "App not reachable";
});
