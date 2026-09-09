// Warns when this browser holds too many live server-sent event streams to
// this app across its tabs.
//
// Over HTTP/1.1 a browser keeps at most six connections open per host, per
// profile. Every stream the app holds — the user stream on every page, the
// session stream on a chat — is one of them, and they never end while their
// page is mounted. So a person with a few chats open in a few tabs reaches
// the limit without doing anything wrong, and from then on every request in
// every tab queues behind the streams: pages stop loading, nothing says why.
//
// The tabs tell each other what they hold over a BroadcastChannel (same
// origin, same profile — exactly the scope of the limit) and each one warns
// when the sum comes within one of the limit. Message handlers run in hidden
// tabs too, unlike timers, so a background tab is counted at once; the
// periodic re-announcement only covers a tab that died without saying
// goodbye. HTTP/2 multiplexes streams over one connection and has no such
// limit, so a page served that way stays quiet.

const LIMIT = 6;
const WARN_AT = LIMIT - 1;
const ANNOUNCE_EVERY_MS = 30_000;
const FORGET_AFTER_MS = 4 * ANNOUNCE_EVERY_MS;   // hidden tabs get one timer a minute
const BANNER_ID = "connection-budget-banner";

/**
 * Wires the watch to the bus. Call once per page; harmless where
 * BroadcastChannel is missing (nothing is counted, nothing is shown).
 */
export function watchConnectionBudget(bus) {
    if (typeof BroadcastChannel !== "function" || !limitApplies()) return;

    const me = Math.random().toString(36).slice(2);
    const peers = new Map();            // tab id → { streams, seen }
    let mine = 0;
    let dismissedAt = 0;                // the total the person waved off
    const channel = new BroadcastChannel("mc-connection-budget");

    const post = (type) => channel.postMessage({ type, id: me, streams: mine });

    channel.addEventListener("message", ({ data }) => {
        if (!data || data.id === me) return;
        if (data.type === "bye") {
            peers.delete(data.id);
        } else {
            peers.set(data.id, { streams: data.streams | 0, seen: Date.now() });
            if (data.type === "hello") post("state");   // a newcomer asks who is here
        }
        review();
    });

    bus.onStreamStateChange(streams => {
        const open = streams.filter(s => s.state === "idle" || s.state === "running").length;
        if (open === mine) return;
        mine = open;
        post("state");
        review();
    });

    window.addEventListener("pagehide", () => post("bye"));
    setInterval(() => { forgetSilent(); post("state"); review(); }, ANNOUNCE_EVERY_MS);
    post("hello");

    function forgetSilent() {
        const cutoff = Date.now() - FORGET_AFTER_MS;
        for (const [id, peer] of peers) if (peer.seen < cutoff) peers.delete(id);
    }

    function review() {
        let total = mine;
        for (const peer of peers.values()) total += peer.streams;
        const tabs = peers.size + 1;
        if (total < WARN_AT) {
            dismissedAt = 0;
            document.getElementById(BANNER_ID)?.remove();
            return;
        }
        // Waving the notice off means "I know" for this count; it comes back
        // only when the count grows further.
        if (dismissedAt && total <= dismissedAt) return;
        showBanner(total, tabs, () => { dismissedAt = total; });
    }
}

/** Only HTTP/1.1 rations connections per host; a page served over h2/h3 is safe. */
function limitApplies() {
    const nav = performance.getEntriesByType?.("navigation")?.[0];
    const protocol = nav?.nextHopProtocol || "";
    return !/^h[23]/.test(protocol);
}

function showBanner(total, tabs, onDismiss) {
    let banner = document.getElementById(BANNER_ID);
    if (!banner) {
        banner = document.createElement("div");
        banner.id = BANNER_ID;
        banner.setAttribute("role", "alert");
        banner.style.cssText = "position:fixed;top:16px;right:16px;max-width:480px;z-index:9999;"
            + "display:flex;align-items:center;gap:12px;padding:10px 14px;"
            + "background:#fef3c7;color:#92400e;border:1px solid #fcd34d;border-radius:6px;"
            + "box-shadow:0 4px 12px rgba(0,0,0,.1);font-size:13px;line-height:1.4;";

        const text = document.createElement("span");
        text.className = "text";
        banner.appendChild(text);

        const close = document.createElement("button");
        close.type = "button";
        close.setAttribute("aria-label", "Dismiss");
        close.textContent = "×";
        close.style.cssText = "font:inherit;font-size:16px;line-height:1;padding:0 4px;border:none;"
            + "background:none;color:inherit;cursor:pointer;";
        close.addEventListener("click", () => { onDismiss(); banner.remove(); });
        banner.appendChild(close);

        document.body.appendChild(banner);
    }
    const where = tabs === 1 ? "in this tab" : `across ${tabs} browser tabs`;
    const verdict = total >= LIMIT
        ? "That is the browser's limit per site: pages will stop loading until some are closed."
        : `The browser allows ${LIMIT} per site; one more and pages stop loading.`;
    banner.querySelector(".text").textContent =
        `⚠ ${total} live connections to this app are open ${where}. ${verdict} Close the tabs you no longer need.`;
}
