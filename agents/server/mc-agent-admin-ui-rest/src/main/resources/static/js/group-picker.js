/*
 * "New group" on the agent form.
 *
 * The group select offers the rubrics that are already in use — read off the
 * agents themselves, because there is no group registry and a value the list
 * only groups by does not earn a table of its own. That leaves one thing the
 * select cannot do: name a rubric that does not exist yet. This adds the
 * button for it, swapping the select for a plain field and back.
 *
 * Injected rather than rendered, like the icon picker and the theme switch:
 * the form is server-rendered, and this is a browser affordance with no
 * request behind it. Exactly one control called `group` is in the DOM at any
 * moment, so the form submits the same way whichever mode it is in — and if
 * the script never loads, the select alone still works.
 */
(function () {
    const NAME = "group";

    function upgrade(select) {
        if (select.dataset.groupPicker === "on") return;
        select.dataset.groupPicker = "on";

        const wrap = document.createElement("div");
        wrap.className = "group-picker";
        select.parentNode.insertBefore(wrap, select);
        wrap.appendChild(select);

        const button = document.createElement("button");
        button.type = "button";                        // never submits the form
        button.className = "group-picker-toggle";
        wrap.appendChild(button);

        // The field the button swaps in. Same name and id as the select, so
        // whichever one is in the DOM is the one the form reads.
        const input = document.createElement("input");
        input.type = "text";
        input.name = NAME;
        input.id = select.id;
        input.placeholder = "New group name…";
        input.className = select.className;

        let typing = false;

        /** Label only — the swap is separate, because the initial state has
            nothing to swap: the select is already the one in the DOM. */
        function label() {
            button.textContent = typing ? "Cancel" : "+ New group";
            button.title = typing
                ? "Back to the existing groups"
                : "Name a group that does not exist yet";
        }

        function toSelect() {
            typing = false;
            if (input.parentNode === wrap) wrap.replaceChild(select, input);
            label();
        }

        function toInput() {
            typing = true;
            input.value = "";
            if (select.parentNode === wrap) wrap.replaceChild(input, select);
            label();
            input.focus();
        }

        button.addEventListener("click", () => (typing ? toSelect() : toInput()));

        // Enter in the new-group field should not submit the surrounding form —
        // the name is not saved until the form itself is saved.
        input.addEventListener("keydown", (e) => {
            if (e.key === "Enter") e.preventDefault();
            if (e.key === "Escape") toSelect();
        });

        toSelect();
    }

    function upgradeAll(root) {
        (root || document).querySelectorAll('select[name="' + NAME + '"]').forEach(upgrade);
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
