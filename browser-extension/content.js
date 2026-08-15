// SentinelX Bridge — content script.
//
// Runs in the page. Finds login fields, offers matching vault logins in a small
// self-contained dropdown when a field is focused, fills the pair after the app
// approves, and offers to capture a newly typed credential on submit.
//
// It only ever sends the page's hostname (never its full URL or contents) to the
// background, and it renders its UI in a shadow root so the page's CSS can't
// restyle it and the page's script can't read it.

(function () {
  const api = typeof browser !== "undefined" ? browser : chrome;
  const domain = location.hostname;
  if (!domain) return;

  const PASS_SEL = 'input[type="password"]';
  let dropdown = null;
  let lastCaptured = { username: "", password: "" };

  // ── Field discovery ────────────────────────────────────────────────────────

  function visible(el) {
    if (!el || el.disabled || el.readOnly) return false;
    const r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0;
  }

  function usernameFor(passwordField) {
    // The username is the text/email field just before the password in document
    // order — the same rule the phone's parser uses.
    const candidates = Array.from(
      document.querySelectorAll('input[type="text"], input[type="email"], input:not([type])')
    ).filter(visible);
    let best = null;
    for (const f of candidates) {
      if (passwordField.compareDocumentPosition(f) & Node.DOCUMENT_POSITION_PRECEDING) {
        best = f;
      }
    }
    return best || candidates[0] || null;
  }

  function loginPair(fromField) {
    const pass = fromField.type === "password"
      ? fromField
      : Array.from(document.querySelectorAll(PASS_SEL)).filter(visible)[0];
    if (!pass) return null;
    return { username: usernameFor(pass), password: pass };
  }

  // ── Dropdown UI (shadow-isolated) ───────────────────────────────────────────

  function removeDropdown() {
    if (dropdown) { dropdown.remove(); dropdown = null; }
  }

  function showDropdown(anchor, candidates) {
    removeDropdown();
    if (!candidates.length) return;
    const host = document.createElement("div");
    host.style.cssText = "position:absolute;z-index:2147483647;";
    const rect = anchor.getBoundingClientRect();
    host.style.left = `${window.scrollX + rect.left}px`;
    host.style.top = `${window.scrollY + rect.bottom + 2}px`;
    host.style.width = `${Math.max(rect.width, 220)}px`;

    const root = host.attachShadow({ mode: "closed" });
    const box = document.createElement("div");
    box.style.cssText =
      "font:13px system-ui,sans-serif;background:#1A1A1E;border:1px solid #D4A85377;" +
      "border-radius:10px;overflow:hidden;box-shadow:0 8px 24px #000A;";
    const header = document.createElement("div");
    header.textContent = "ᛀ  SENTINELX";
    header.style.cssText = "padding:7px 12px;color:#D4A853;font-weight:700;letter-spacing:2px;font-size:10px;";
    box.appendChild(header);

    candidates.forEach((c) => {
      const row = document.createElement("div");
      row.style.cssText = "padding:9px 12px;cursor:pointer;border-top:1px solid #2A2A30;";
      const title = document.createElement("div");
      title.textContent = c.siteName;
      title.style.cssText = "color:#EAE0CC;font-weight:600;";
      const sub = document.createElement("div");
      sub.textContent = c.username;
      sub.style.cssText = "color:#AA9E88;font-size:11px;";
      row.appendChild(title);
      row.appendChild(sub);
      row.addEventListener("mouseenter", () => (row.style.background = "#26262C"));
      row.addEventListener("mouseleave", () => (row.style.background = "transparent"));
      row.addEventListener("mousedown", (e) => {
        e.preventDefault();
        chooseCandidate(anchor, c);
      });
      box.appendChild(row);
    });

    root.appendChild(box);
    document.body.appendChild(host);
    dropdown = host;
  }

  async function chooseCandidate(anchor, candidate) {
    removeDropdown();
    const reply = await api.runtime.sendMessage({ type: "fill", id: candidate.id, domain });
    if (!reply || reply.type !== "secret") return; // denied / unavailable — stay quiet
    const pair = loginPair(anchor);
    if (!pair) return;
    if (pair.username && reply.username) setValue(pair.username, reply.username);
    if (pair.password) setValue(pair.password, reply.password);
  }

  function setValue(field, value) {
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, "value").set;
    setter.call(field, value);
    field.dispatchEvent(new Event("input", { bubbles: true }));
    field.dispatchEvent(new Event("change", { bubbles: true }));
  }

  // ── Focus → query ───────────────────────────────────────────────────────────

  async function onFocus(e) {
    const field = e.target;
    if (!(field instanceof HTMLInputElement)) return;
    const isLogin =
      field.type === "password" ||
      field.type === "email" ||
      (field.type === "text" && loginPair(field));
    if (!isLogin) return;
    const reply = await api.runtime.sendMessage({ type: "query", domain });
    if (reply && reply.type === "matches" && Array.isArray(reply.candidates)) {
      showDropdown(field, reply.candidates);
    }
  }

  document.addEventListener("focusin", onFocus, true);
  document.addEventListener("mousedown", (e) => {
    if (dropdown && !dropdown.contains(e.target)) removeDropdown();
  }, true);
  window.addEventListener("scroll", removeDropdown, true);

  // ── Submit → capture ────────────────────────────────────────────────────────

  function snapshot() {
    const pass = Array.from(document.querySelectorAll(PASS_SEL)).filter(visible)[0];
    if (!pass || !pass.value) return;
    const user = usernameFor(pass);
    lastCaptured = { username: user ? user.value : "", password: pass.value };
  }

  document.addEventListener("submit", () => {
    snapshot();
    if (lastCaptured.password) offerCapture();
  }, true);

  // Some sites log in without a form submit (JS button). A password field losing
  // its value after having one is the other reliable "just submitted" signal.
  document.addEventListener("focusout", (e) => {
    if (e.target instanceof HTMLInputElement && e.target.type === "password" && e.target.value) {
      snapshot();
    }
  }, true);
  window.addEventListener("beforeunload", () => {
    if (lastCaptured.password) offerCapture();
  });

  let captureSent = false;
  function offerCapture() {
    if (captureSent || !lastCaptured.password) return;
    captureSent = true;
    // The app decides whether this is new and shows its own confirm sheet;
    // the extension just reports what was typed.
    api.runtime.sendMessage({
      type: "capture",
      domain,
      username: lastCaptured.username,
      password: lastCaptured.password,
    });
  }
})();
