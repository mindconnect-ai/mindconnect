/*
 * The theme picker in the header.
 *
 * Which theme is on is a decision about this browser, not about the account:
 * it changes no data, the server has no opinion on it, and two people sharing
 * a login should be able to disagree. So it lives entirely on the client —
 * localStorage plus a class on <html> — and nothing here talks to the server.
 *
 * That is also why the control is injected rather than rendered. The header is
 * part of the server-rendered UiNode tree, and a purely client-side switch has
 * no business in it; the same escape hatch the chat's row menus use applies
 * here. The markup below is exactly what the framework's own UiMenuButton
 * renders, so it inherits the popover styling and the enhancer that positions
 * it — only the click handling is ours, because there is no request to make.
 *
 * The initial class is set by the inline script in index.html, not here: this
 * file loads after first paint, and a theme applied that late would flash the
 * previous one first. That script sets whatever it finds; this one owns the
 * list of what is real.
 */
(function () {
    const STORAGE_KEY = "sui-theme";
    const THEMES = [
        { id: "clody",    label: "Clody",    hint: "Warm, one plane" },
        { id: "gipiti",   label: "Gipiti",   hint: "Neutral, two planes" },
        { id: "dark",     label: "Dark",     hint: "The framework's own dark" },
        { id: "amethyst", label: "Amethyst", hint: "Violet on a dark ground" },
        { id: "default",  label: "Default",  hint: "The framework's own" },
    ];

    const PALETTE_ICON =
        '<svg class="sui-icon" aria-hidden="true">' +
        '<use href="/sui/icons.svg#palette"></use></svg>';

    function current() {
        let stored = null;
        try {
            stored = localStorage.getItem(STORAGE_KEY);
        } catch (e) {
            // Private mode, or storage disabled. The switch still works for
            // this page; it just will not be remembered.
        }
        if (stored && THEMES.some((t) => t.id === stored)) return stored;
        return "clody";
    }

    /** The one place the class is actually swapped. */
    function apply(theme) {
        THEMES.forEach((t) => {
            document.documentElement.classList.toggle(
                "sui-theme-" + t.id, t.id === theme && t.id !== "default");
        });
        try {
            localStorage.setItem(STORAGE_KEY, theme);
        } catch (e) {
            // See current(): not being remembered is survivable.
        }
    }

    function build() {
        const active = current();
        const el = document.createElement("details");
        el.className = "sui-menu-button sui-menu-button--icon sui-menu-button--align-end sui-theme-switch";
        el.dataset.sui = "menu-button";
        el.innerHTML =
            '<summary class="sui-menu-button-trigger" role="button" aria-haspopup="menu"' +
            ' aria-expanded="false" aria-label="Theme">' +
            '<span class="sui-menu-button-glyph">' + PALETTE_ICON + "</span></summary>" +
            '<div class="sui-menu-button-popover" role="menu">' +
            THEMES.map((t) =>
                '<button type="button" class="sui-menu-button-item" role="menuitemradio"' +
                ' aria-checked="' + (t.id === active) + '" data-theme="' + t.id + '">' +
                '<span class="sui-menu-button-item-label">' + t.label + "</span>" +
                '<span class="sui-theme-switch-hint">' + t.hint + "</span>" +
                "</button>").join("") +
            "</div>";

        el.addEventListener("click", function (event) {
            const item = event.target.closest("[data-theme]");
            if (!item) return;
            event.preventDefault();
            apply(item.dataset.theme);
            el.querySelectorAll("[data-theme]").forEach((b) => {
                b.setAttribute("aria-checked", String(b === item));
            });
            el.open = false;   // the framework closes on a dispatch; there is none here
        });
        return el;
    }

    /** Puts the control in the header, ahead of the user widget. */
    function inject() {
        const right = document.querySelector(".sui-header .sui-header-right");
        if (!right || right.querySelector(".sui-theme-switch")) return;
        right.insertBefore(build(), right.firstChild);
    }

    // The header is re-rendered by navigations and by patches that redraw the
    // shell, and each time it comes back without the control. One watcher on
    // the document, coalesced into a single pass, puts it back — the same
    // shape as the chat's row-menu decoration, and for the same reason.
    let scheduled = false;
    function schedule() {
        if (scheduled) return;
        scheduled = true;
        // A timeout rather than requestAnimationFrame: rAF does not run in a
        // background tab, and a page opened in one would come up without the
        // switch and stay that way until it was looked at.
        setTimeout(function () {
            scheduled = false;
            inject();
        }, 0);
    }

    new MutationObserver(schedule).observe(document.documentElement, {
        childList: true,
        subtree: true,
    });

    document.addEventListener("DOMContentLoaded", schedule);
    schedule();
})();
