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

    /* ── The drawer's close button ────────────────────────────────────────
     * UiAppShell switches a menu's own toggle off when it wires the menu to
     * a header's burger, and the chat has no header any more — so the drawer
     * renders without one. The button belongs to the drawer, not to the
     * server's idea of the page, so it is built here, in the framework's own
     * icon-button markup, and re-added whenever the menu is re-rendered.
     */
    function addDrawerClose() {
        const menu = document.getElementById(MENU_ID);
        if (!menu) return;
        const head = menu.querySelector(".sui-menu-head");
        if (!head || head.querySelector(".chat-drawer-close")) return;
        const button = document.createElement("button");
        button.type = "button";
        button.className = "sui-icon-btn sui-icon-btn--secondary chat-drawer-close";
        button.setAttribute("aria-label", "Close");
        // Lucide panel-left-close — the mirror twin of the panel-left-open
        // that opened the drawer. Referenced from the sprite so it stays in
        // step with the open button's rendering.
        button.innerHTML =
            '<svg class="sui-icon" width="18" height="18" aria-hidden="true">' +
            '<use href="/sui/icons.svg#panel-left-close"></use></svg>';
        button.addEventListener("click", function (event) {
            event.preventDefault();
            event.stopPropagation();
            apply(menu, "hidden");
        });
        head.appendChild(button);
    }

    /* ── What the rows say about their chats ──────────────────────────────
     * The server renders a busy chat's row with "running" or "needs input"
     * in place of its age (see ChatShellComponent); the user stream carries
     * the changes after that. The menu renderer has no slot for a class on
     * the row, so the state is read off the badge and turned into one here
     * — that is what the stylesheet colours.
     */
    const BADGE_RUNNING = "running";
    const BADGE_NEEDS_INPUT = "needs input";
    // A chat started elsewhere that this browser has not opened yet.
    const BADGE_NEW = "new";

    function markRow(row) {
        const badge = row.querySelector(".sui-menu-badge");
        const text = badge ? badge.textContent.trim() : "";
        row.classList.toggle("is-running", text === BADGE_RUNNING);
        row.classList.toggle("needs-input", text === BADGE_NEEDS_INPUT);
        row.classList.toggle("is-new", text === BADGE_NEW);
        // Remembered on the row: a turn in the chat replaces the badge for a
        // while, and "new" has to come back when it ends.
        if (text === BADGE_NEW) row.dataset.unseen = "1";
    }

    function setBadge(row, text) {
        let badge = row.querySelector(".sui-menu-badge");
        if (!badge) {
            badge = document.createElement("span");
            badge.className = "sui-menu-badge";
            const link = row.querySelector(".sui-menu-link");
            (link || row).appendChild(badge);
        }
        badge.textContent = text;
        markRow(row);
    }

    /*
     * A chat started elsewhere — another tab, the REST API — has no row until
     * the page is rendered again, so the user would not know it exists. It
     * gets one now, cloned from a row the server rendered so the markup is
     * the framework's, and marked new until it is opened (the server stops
     * marking it once it has been on screen).
     */
    function addNewRow(sessionId) {
        const menu = document.getElementById(MENU_ID);
        if (!menu) return null;
        const rows = Array.from(menu.querySelectorAll("li.sui-menu-item"))
            .filter((r) => (r.dataset.id || "").startsWith("chat-") && r.dataset.id !== "chat-new"
                && r.querySelector("a.sui-menu-link"));
        if (rows.length === 0) return null; // nothing to model the row on; the next render shows it
        const row = rows[0].cloneNode(true);
        row.id = "chat-" + sessionId;
        row.dataset.id = "chat-" + sessionId;
        row.classList.remove("is-running", "needs-input", "is-new", "chat-row");
        Array.from(row.classList).filter((c) => /active|selected/.test(c)).forEach((c) => row.classList.remove(c));
        row.querySelector(".chat-row-menu")?.remove();
        const link = row.querySelector("a.sui-menu-link");
        const href = "/chat/sessions/" + sessionId;
        link.setAttribute("href", href);
        if (link.hasAttribute("data-href")) link.setAttribute("data-href", href);
        link.removeAttribute("aria-current");
        const label = row.querySelector(".sui-menu-label");
        if (label) label.textContent = "New chat";
        const tip = row.querySelector(".sui-menu-tip");
        if (tip) tip.textContent = "New chat";
        rows[0].parentNode.insertBefore(row, rows[0]);
        setBadge(row, BADGE_NEW);
        return row;
    }

    /** One event of the user's stream, applied to the row it concerns. */
    function applyUserEvent(event) {
        if (!event || !event.sessionId) return;
        const row = document.getElementById("chat-" + event.sessionId);
        if (!row) {
            if (event.type === "session_started") addNewRow(event.sessionId);
            return;
        }
        switch (event.type) {
            case "turn_started":
                setBadge(row, BADGE_RUNNING);
                break;
            case "approval_requested":
                setBadge(row, BADGE_NEEDS_INPUT);
                break;
            case "approval_answered":
                // The turn goes on; the card is gone.
                if (row.classList.contains("needs-input")) setBadge(row, BADGE_RUNNING);
                break;
            case "turn_finished":
                setBadge(row, row.dataset.unseen ? BADGE_NEW : "now");
                break;
            case "session_titled": {
                const label = row.querySelector(".sui-menu-label");
                if (label && event.title) label.textContent = event.title;
                const tip = row.querySelector(".sui-menu-tip");
                if (tip && event.title) tip.textContent = event.title;
                break;
            }
            default:
                break;
        }
    }

    document.addEventListener("mc-user-event", (e) => applyUserEvent(e.detail));

    /** Gives every history row its menu; runs again after each patch. */
    function decorateRows() {
        const menu = document.getElementById(MENU_ID);
        if (!menu) return;
        menu.querySelectorAll("li.sui-menu-item").forEach((row) => {
            const id = (row.dataset.id || "");
            if (!id.startsWith("chat-") || id === "chat-new") return;
            markRow(row);
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
    /* ── The composer's placeholder ───────────────────────────────────────
     * UiField carries a placeholder and the server sets one, but the
     * framework's field template only writes the attribute for the input
     * branches — the textarea branch drops it. So the value is read back out
     * of the form's own node JSON, where the server already put it, and set
     * on the element.
     *
     * Delete this the day field.hbs renders placeholder on <textarea>: the
     * server side needs no change, the attribute simply arrives on its own.
     */
    function applyPlaceholder() {
        document.querySelectorAll("form.chat-form[data-node]").forEach(function (form) {
            const area = form.querySelector("textarea");
            if (!area || area.placeholder) return;
            let node;
            try {
                node = JSON.parse(form.dataset.node);
            } catch (e) {
                return;
            }
            const field = (node.fields || []).find(function (f) { return f.id === area.name; });
            if (field && field.placeholder) area.placeholder = field.placeholder;
        });
    }

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
            applyPlaceholder();
            addDrawerClose();
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
            // Two ways to open it: the framework's own toggle inside the
            // drawer, and the button in the conversation's header — which is
            // a plain action, because whether a drawer is open is client
            // state and there is nothing to ask the server.
            // By id, not by class: UiAction's icon appearance does not carry
            // a cssClass through to the button, so a class selector here
            // matched nothing and the button did nothing. The id it is given
            // server-side does arrive.
            const button = event.target.closest(
                '[data-menu-toggle="' + MENU_ID + '"], #chat-history');
            if (!button) return;
            const menu = document.getElementById(MENU_ID);
            if (!menu) return;

            event.preventDefault();
            event.stopPropagation();
            apply(menu, menu.dataset.menuState === "hidden" ? "expanded" : "hidden");
        },
        true
    );

    // Clicking the scrim closes it, the way any drawer does.
    document.addEventListener(
        "click",
        function (event) {
            if (!event.target.classList.contains("sui-menu-backdrop")) return;
            const menu = document.getElementById(MENU_ID);
            if (menu) apply(menu, "hidden");
        },
        true
    );
})();
