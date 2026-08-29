/*
 * Two states for the chat's history, not three.
 *
 * The semantic-ui menu renderer cycles expanded → rail → hidden, and in
 * responsive mode a wide screen only ever flips expanded ⇄ rail — the sidebar
 * never fully goes away. For a chat that is wrong: either you are picking a
 * conversation, or you want the width for the one you are in. A rail of
 * icons serves neither.
 *
 * So the chat's own toggle is handled here, in the capture phase, before the
 * framework's click handler sees it. Everything else about the menu — the
 * markup, the classes, the transitions — stays the framework's.
 */
(function () {
    const MENU_ID = "chat-menu";
    const STATES = ["expanded", "rail", "hidden"];

    function apply(menu, state) {
        STATES.forEach((s) => menu.classList.toggle("sui-menu--" + s, s === state));
        menu.dataset.menuState = state;
        const toggle = menu.querySelector(".sui-menu-toggle");
        if (toggle) toggle.setAttribute("aria-expanded", String(state !== "hidden"));
        const header = document.querySelector('[data-menu-toggle="' + MENU_ID + '"]');
        if (header) header.setAttribute("aria-expanded", String(state !== "hidden"));
    }

    /* ── Per-chat actions in the history ──────────────────────────────────
     * The menu renderer has no slot for row actions: an item with children
     * becomes a <details> expander, which is not what a "rename / delete"
     * affordance should feel like. So the row gets a real UiMenuButton built
     * here, in exactly the markup the framework renders server-side — a
     * native <details> whose entries carry data-trigger, so the event bus
     * dispatches them like any other action and no bespoke fetching happens.
     */
    const MORE_SVG =
        '<svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true">' +
        '<circle cx="5" cy="12" r="1.6" fill="currentColor"/>' +
        '<circle cx="12" cy="12" r="1.6" fill="currentColor"/>' +
        '<circle cx="19" cy="12" r="1.6" fill="currentColor"/></svg>';

    function trigger(method, url) {
        return JSON.stringify({ url: url, method: method, behavior: "APPLY_RESPONSE" });
    }

    function buildRowMenu(sessionId) {
        const el = document.createElement("details");
        el.className =
            "sui-menu-button sui-menu-button--icon sui-menu-button--align-end chat-row-menu";
        el.dataset.sui = "menu-button";
        el.innerHTML =
            '<summary class="sui-menu-button-trigger" role="button" aria-haspopup="menu"' +
            ' aria-expanded="false" aria-label="Chat actions">' +
            '<span class="sui-menu-button-glyph">' + MORE_SVG + "</span></summary>" +
            '<div class="sui-menu-button-popover" role="menu">' +
            '<button type="button" class="sui-menu-button-item" role="menuitem"' +
            " data-trigger='" + trigger("GET", "/chat/api/sessions/" + sessionId + "/rename") + "'>" +
            '<span class="sui-menu-button-item-label">Rename</span></button>' +
            '<button type="button" class="sui-menu-button-item is-danger" role="menuitem"' +
            " data-trigger='" + trigger("POST", "/chat/api/sessions/" + sessionId + "/delete") + "'" +
            ' data-confirm="Delete this chat and its whole conversation?">' +
            '<span class="sui-menu-button-item-label">Delete</span></button>' +
            "</div>";
        return el;
    }

    /** Gives every history row its menu; runs again after each patch. */
    function decorateRows() {
        const menu = document.getElementById(MENU_ID);
        if (!menu) return;
        menu.querySelectorAll("li.sui-menu-item").forEach((row) => {
            const id = (row.dataset.id || "");
            if (!id.startsWith("chat-") || id === "chat-new") return;
            if (row.querySelector(".chat-row-menu")) return;
            row.classList.add("chat-row");
            row.appendChild(buildRowMenu(id.slice("chat-".length)));
        });
    }

    /*
     * The rows are decorated after every render, coalesced into one animation
     * frame. Watching only the menu is not enough: the SPA replaces the whole
     * page inside its mount, menu and all, so the observer has to sit above
     * it. The frame gate is what keeps this cheap — a streaming turn patches
     * the message list many times a second, and this runs at most once per
     * frame, over one querySelectorAll scoped to the menu.
     */
    let scheduled = false;
    function schedule() {
        if (scheduled) return;
        scheduled = true;
        // A timeout, not requestAnimationFrame: rAF does not run in a hidden
        // tab, so a chat opened in a background tab came up with undecorated
        // rows and stayed that way until it was looked at. The flag still
        // collapses a burst of mutations into one pass.
        setTimeout(function () {
            scheduled = false;
            decorateRows();
        }, 0);
    }

    // Deliberately the document: the SPA re-renders the page inside its mount
    // and the mount element itself is not guaranteed to survive, so an
    // observer anchored below the root goes deaf after the first navigation —
    // which is exactly what happened when this watched #main. The frame gate
    // above is what makes the wide scope affordable.
    new MutationObserver(schedule).observe(document.documentElement, {
        childList: true,
        subtree: true,
    });

    document.addEventListener("DOMContentLoaded", schedule);
    schedule();

    document.addEventListener(
        "click",
        function (event) {
            const button = event.target.closest('[data-menu-toggle="' + MENU_ID + '"]');
            if (!button) return;
            const menu = document.getElementById(MENU_ID);
            if (!menu) return;

            event.preventDefault();
            event.stopPropagation();
            apply(menu, menu.dataset.menuState === "hidden" ? "expanded" : "hidden");
        },
        true
    );
})();
