// Renderer + event bus + BFF helpers ship with the mc-semantic-ui-* JARs
// (Spring Boot serves the JAR contents at /sui/*). The JAR is the single
// source of truth: a framework-side bugfix lands in every app via a version
// bump instead of per-app copy-paste.
import { SuiRenderer, installDefaultHandlers } from "/sui/renderer.js";
import { SuiEventBus }                         from "/sui/eventbus.js";
import { bffFetch }                            from "/sui/bff.js";
import { install as installJsonViewer } from "/sui-ext/jsonviewer/extension.js";
import { install as installMarkdown }   from "/sui-ext/markdown/extension.js";
import { install as installDiagram }    from "/sui-ext/diagram/extension.js";

// ── Renderer + extensions ────────────────────────────────────────────────────

const main = document.getElementById("main");

const renderer = installDefaultHandlers(new SuiRenderer(main));
await Promise.all([
    installJsonViewer(renderer),
    installMarkdown(renderer),
]);
// Teaches the renderer to draw UiDiagram nodes (the embedded workflow admin's
// "Diagram" tab). Synchronous install — the web component registers itself.
installDiagram(renderer);

// The header (brand, nav, user widget, optional logout) is part of the
// server-rendered UiNode tree now (see AdminLayout). Nav links and the logout
// link live inside #main, so the EventBus handles their clicks automatically —
// no manual wiring or /me fetch needed here.

// ── App-specific config ──────────────────────────────────────────────────────

// UI paths ARE API paths: every admin section answers JSON on the very URL
// the browser navigates to (content negotiation; the AdminSameUrlFilter
// forwards legacy /admin/<section> JSON GETs to /admin/api/<section> on the
// server side). No client-side rewriting.

/**
 * 401 handler: navigate the browser to the {@code /login} landing
 * page. That page is plain HTML served by Spring with a single
 * "Sign in with Keycloak" button — no automatic redirect, no JS.
 * If anything in the OAuth flow fails, the user lands back on
 * {@code /login?error=…} with an explanation and can retry by
 * clicking the button. No loops are possible.
 */
function handle401() {
    window.location.href = "/login";
    return true;
}

// ── Event bus wiring ─────────────────────────────────────────────────────────

const bus = new SuiEventBus(renderer, main);
bus.setFetcher(bffFetch)
   .setOnUnauthenticated(handle401)
   // App-defined SSE event: chat errors come over the same stream as patches.
   .onStreamEvent("error", (msg) => showStreamError(msg))
   // The framework tells us when a stream's state changes; what to do about
   // a lost one is our call.
   .onStreamStateChange(noticeLostStream);

// ── "Connection lost" notice ─────────────────────────────────────────────────

/**
 * A session's stream never ends on its own while its page is mounted — it
 * belongs to the session, not to a turn. So a stream that reaches "completed"
 * or "errored" with its page still attached did not finish: the server went
 * away (a restart, a crash) and took the running turn with it. The client
 * registers that internally and would otherwise say nothing, leaving the
 * composer on "AI is thinking" for good and a half-streamed reply frozen.
 *
 * This is a notice, deliberately not an automatic re-fetch of the page. A
 * re-fetch would re-attach the stream, and a connection that keeps dropping —
 * a proxy cutting idle connections, say — would then re-fetch the page in a
 * loop. A person clicking Reload cannot loop.
 */
function noticeLostStream(streams) {
    const suspect = streams.some(s => s.pageAttached
            && (s.state === "completed" || s.state === "errored"));
    if (!suspect || document.getElementById("stream-lost-banner") || noticeLostStream._probing) return;
    // "completed" is not proof. The framework also uses it for a turn that
    // finished while the page was away, and the handle keeps that state for a
    // few seconds after the page comes back — long enough to look exactly
    // like a lost connection. So ask the server before claiming it is gone:
    // a reachable server means nothing was lost; a dead one cannot answer.
    noticeLostStream._probing = true;
    fetch("/chat/api/streams", { headers: { Accept: "application/json" }, cache: "no-store" })
        .then(res => { if (!res.ok && res.status >= 500) showLostStreamBanner(); })
        .catch(showLostStreamBanner)
        .finally(() => { noticeLostStream._probing = false; });
}

function showLostStreamBanner() {
    if (document.getElementById("stream-lost-banner")) return;

    const banner = document.createElement("div");
    banner.id = "stream-lost-banner";
    banner.setAttribute("role", "alert");
    banner.style.cssText = "position:fixed;top:16px;right:16px;max-width:480px;z-index:9999;"
        + "display:flex;align-items:center;gap:12px;padding:10px 14px;"
        + "background:#fef3c7;color:#92400e;border:1px solid #fcd34d;border-radius:6px;"
        + "box-shadow:0 4px 12px rgba(0,0,0,.1);font-size:13px;line-height:1.4;";

    const text = document.createElement("span");
    text.textContent = "⚠ Connection to the server was lost. What you see may be out of date.";
    banner.appendChild(text);

    const reload = document.createElement("button");
    reload.type = "button";
    reload.textContent = "Reload";
    reload.style.cssText = "font:inherit;font-weight:600;padding:4px 10px;border-radius:4px;"
        + "border:1px solid #d97706;background:#fff;color:#92400e;cursor:pointer;";
    reload.addEventListener("click", () => location.reload());
    banner.appendChild(reload);

    const close = document.createElement("button");
    close.type = "button";
    close.setAttribute("aria-label", "Dismiss");
    close.textContent = "×";
    close.style.cssText = "font:inherit;font-size:16px;line-height:1;padding:0 4px;border:none;"
        + "background:none;color:inherit;cursor:pointer;";
    close.addEventListener("click", () => banner.remove());
    banner.appendChild(close);

    document.body.appendChild(banner);
}

// ── Transient error banner used by the streaming chat ───────────────────────

function showStreamError(message) {
    let banner = document.getElementById("stream-error-banner");
    if (!banner) {
        banner = document.createElement("div");
        banner.id = "stream-error-banner";
        banner.className = "sui-error";
        banner.style.cssText = "position:fixed;top:16px;right:16px;max-width:480px;z-index:9999;padding:10px 14px;background:#fee2e2;color:#991b1b;border:1px solid #fca5a5;border-radius:6px;box-shadow:0 4px 12px rgba(0,0,0,.1);font-size:13px;line-height:1.4;";
        document.body.appendChild(banner);
    }
    banner.textContent = "⚠ " + message;
    clearTimeout(showStreamError._t);
    showStreamError._t = setTimeout(() => banner.remove(), 10000);
}

// ── Embedded workflow admin: click a diagram node to open its step dialog ───

main.addEventListener("sui-diagram-node-selected", async (event) => {
    const stepRef = event.detail && event.detail.stepRef;
    if (!stepRef) return;                        // synthetic nodes (start/end/branch)
    const wf = workflowFromUrl();
    if (!wf) return;
    const res = await fetch(`/workflow-admin/${wf}/step/${encodeURIComponent(stepRef)}`,
                            { headers: { Accept: "application/json" } });
    if (res.ok) bus.applyPatch(await res.json());
});

/** The workflow name is the second path segment: /workflow-admin/{wf}[/...]. */
function workflowFromUrl() {
    const segments = window.location.pathname.split("/").filter(Boolean);
    return segments[0] === "workflow-admin" ? segments[1] : null;
}

// ── Initial load (bus wires popstate itself) ────────────────────────────────
// Keep the query string: a reload or deep link on e.g. /admin/agents?page=2&q=x
// must land on that page, not silently reset to the defaults.

bus.start(window.location.pathname === "/"
    ? "/admin/agents"
    : window.location.pathname + window.location.search);
