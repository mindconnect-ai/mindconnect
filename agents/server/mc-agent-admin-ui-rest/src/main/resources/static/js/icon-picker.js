/*
 * The icon picker on the agent form.
 *
 * The stored value is a Lucide name — the id of a symbol in the framework's
 * sprite, which is what `<use href="/sui/icons.svg#name">` needs. Typing that
 * name from memory is no way to choose among two thousand icons, so the plain
 * text field grows a button that opens a searchable grid; picking writes the
 * name back into the field, which is still the thing the form submits.
 *
 * Injected rather than rendered, like the theme switch: the field is part of
 * the server-rendered UiNode tree, and a picker is a browser affordance with
 * no request behind it. Keeping the input as the source of truth also means
 * the form works unchanged when this script never loads — you can still type
 * a name.
 *
 * The SPA replaces the form on every navigation, so the upgrade runs from a
 * MutationObserver and marks what it has already done.
 */
(function () {
    const CATALOG_URL = "/admin/api/icons";
    const SPRITE = "/sui/icons.svg";
    /** Enough to scan, few enough to stay responsive while typing. */
    const MAX_SHOWN = 240;

    let catalogPromise = null;

    /** The icon names, fetched once per page. */
    function catalog() {
        if (!catalogPromise) {
            catalogPromise = fetch(CATALOG_URL, { headers: { Accept: "application/json" } })
                .then((r) => (r.ok ? r.json() : []))
                .catch(() => []);
        }
        return catalogPromise;
    }

    function iconSvg(name) {
        return '<svg class="sui-icon" aria-hidden="true">'
            + '<use href="' + SPRITE + "#" + name + '"></use></svg>';
    }

    function upgrade(input) {
        if (input.dataset.iconPicker === "on") return;
        input.dataset.iconPicker = "on";

        const wrap = document.createElement("div");
        wrap.className = "icon-picker";
        input.parentNode.insertBefore(wrap, input);
        wrap.appendChild(input);

        const trigger = document.createElement("button");
        trigger.type = "button";                       // never submits the form
        trigger.className = "icon-picker-trigger";
        trigger.setAttribute("aria-label", "Choose an icon");
        wrap.appendChild(trigger);

        const pop = document.createElement("div");
        pop.className = "icon-picker-popover";
        pop.hidden = true;
        pop.innerHTML =
            '<input type="search" class="icon-picker-search" placeholder="Search icons…" '
            + 'aria-label="Search icons">'
            + '<div class="icon-picker-grid" role="listbox"></div>'
            + '<p class="icon-picker-note"></p>';
        wrap.appendChild(pop);

        const search = pop.querySelector(".icon-picker-search");
        const grid = pop.querySelector(".icon-picker-grid");
        const note = pop.querySelector(".icon-picker-note");

        /** The button shows what is currently chosen — or that nothing is. */
        function paintTrigger() {
            const name = (input.value || "").trim();
            trigger.innerHTML = name ? iconSvg(name) : iconSvg("image-off");
            trigger.classList.toggle("is-empty", !name);
            trigger.title = name || "No icon";
        }

        function paintGrid(names, query) {
            const shown = names.slice(0, MAX_SHOWN);
            grid.innerHTML = shown
                .map((n) => '<button type="button" role="option" class="icon-picker-item" '
                    + 'data-icon="' + n + '" title="' + n + '">' + iconSvg(n) + "</button>")
                .join("");
            if (!names.length) {
                note.textContent = query ? "Nothing matches “" + query + "”." : "No icons available.";
            } else if (names.length > shown.length) {
                note.textContent = names.length + " matches, showing the first " + shown.length
                    + " — keep typing to narrow it down.";
            } else {
                note.textContent = names.length + (names.length === 1 ? " icon" : " icons");
            }
        }

        function filter(all) {
            const q = search.value.trim().toLowerCase();
            if (!q) return paintGrid(all, q);
            // Names that start with the query first: typing "book" should lead
            // with `book`, not with `address-book`.
            const starts = [], contains = [];
            for (const n of all) {
                if (n.startsWith(q)) starts.push(n);
                else if (n.includes(q)) contains.push(n);
            }
            paintGrid(starts.concat(contains), q);
        }

        function open() {
            pop.hidden = false;
            catalog().then((all) => {
                filter(all);
                search.oninput = () => filter(all);
            });
            search.value = "";
            search.focus();
            document.addEventListener("mousedown", onOutside, true);
            document.addEventListener("keydown", onKey, true);
        }

        function close() {
            pop.hidden = true;
            document.removeEventListener("mousedown", onOutside, true);
            document.removeEventListener("keydown", onKey, true);
        }

        function onOutside(e) {
            if (!wrap.contains(e.target)) close();
        }

        function onKey(e) {
            if (e.key === "Escape") { close(); trigger.focus(); }
        }

        trigger.addEventListener("click", () => (pop.hidden ? open() : close()));

        grid.addEventListener("click", (e) => {
            const item = e.target.closest(".icon-picker-item");
            if (!item) return;
            input.value = item.dataset.icon;
            // The renderer reads the form from the DOM, but an input event
            // keeps any listener in step with a value set from script.
            input.dispatchEvent(new Event("input", { bubbles: true }));
            input.dispatchEvent(new Event("change", { bubbles: true }));
            paintTrigger();
            close();
        });

        // Typing a name by hand still works, and the button follows along.
        input.addEventListener("input", paintTrigger);
        paintTrigger();
    }

    function upgradeAll(root) {
        (root || document).querySelectorAll('input[name="icon"]').forEach(upgrade);
    }

    function start() {
        upgradeAll(document);
        const main = document.getElementById("main");
        if (!main) return;
        new MutationObserver(() => upgradeAll(main)).observe(main, { childList: true, subtree: true });
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", start);
    } else {
        start();
    }
})();
