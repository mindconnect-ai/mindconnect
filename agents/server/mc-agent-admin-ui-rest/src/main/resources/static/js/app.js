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
   // The framework publishes stream state; what it looks like is ours.
   .onStreamStateChange(renderStreamStatus);

// ── Floating "still running" indicator ──────────────────────────────────────

/**
 * Shows what is running somewhere the user cannot see. Only streams whose
 * page is NOT mounted qualify: while you are looking at the chat, the chat
 * itself is the status display.
 *
 * The wording lives here rather than in the framework, which moves bytes and
 * has no idea whether a stream carries a chat, a report or an import. The
 * destination names itself through `returnLabel` (the chat page sends its
 * session title); `returnHref` is where the button goes.
 */
function renderStreamStatus(streams) {
    const away = streams.filter(s => !s.pageAttached);
    const running = away.filter(s => s.state === "running");
    const finished = away.filter(s => s.state === "completed" || s.state === "errored");

    const existing = document.getElementById("stream-status-toast");
    if (running.length === 0 && finished.length === 0) {
        if (existing) existing.remove();
        return;
    }

    // Prefer what still needs waiting for; an answer already sitting there
    // can wait a moment longer.
    const isRunning = running.length > 0;
    const focus = isRunning ? running[0] : finished[0];
    const what = focus.returnLabel || focus.label || "Agent";

    const toast = existing || document.createElement("div");
    if (!existing) {
        toast.id = "stream-status-toast";
        toast.className = "sui-toast sui-toast--info stream-status";
        toast.setAttribute("role", "status");
        document.body.appendChild(toast);
    }
    toast.replaceChildren();

    const text = document.createElement("span");
    text.textContent = isRunning ? `${what} is working…` : `${what}: answer ready`;
    toast.appendChild(text);

    const link = document.createElement("button");
    link.type = "button";
    link.className = "sui-toast-link";
    link.textContent = isRunning ? "Watch" : "Read it";
    link.addEventListener("click", () => { void bus.navigate(focus.returnHref); });
    toast.appendChild(link);
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
