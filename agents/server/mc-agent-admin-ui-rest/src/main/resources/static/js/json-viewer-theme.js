/*
 * The JSON viewer takes its colours from the active theme.
 *
 * <andypf-json-viewer> paints itself from a base16 palette that it writes
 * into a <style> inside its own shadow root, as `.container{--base00: …}`.
 * That is a declaration on the very element the colours apply to, so nothing
 * in app.css can reach it: a --base00 set on the host is only inherited, and
 * an inherited value loses to the element's own rule. The palette has to be
 * handed to the component; it cannot be styled around it.
 *
 * So this file translates the theme into base16 and hands it over as the
 * component's `theme` attribute, which takes a JSON palette as readily as a
 * theme name. The colours are read off <html> rather than kept in a table
 * per theme: a new theme then needs no entry here, and one that retunes its
 * tokens is followed without a second edit. The server no longer names a
 * theme at all — it cannot, the choice lives in this browser's localStorage.
 *
 * An attribute rather than the .theme property on purpose: the component is
 * loaded lazily from a CDN, so elements exist before their definition does,
 * and a property set on a not-yet-upgraded element would shadow the setter
 * that upgrade installs. An attribute is replayed into the component when
 * the definition lands.
 */
(function () {
    const TAG = "andypf-json-viewer";

    function token(styles, name, fallback) {
        return styles.getPropertyValue("--sui-color-" + name).trim() || fallback;
    }

    /*
     * The comments are what the component does with each slot, not base16
     * convention — that is what decides which ones have to stay legible.
     * The fallbacks matter: the framework's own stylesheet uses
     * --sui-color-warning and --sui-color-success without defining them, so
     * on the default look those two arrive empty.
     */
    function palette() {
        const s = getComputedStyle(document.documentElement);
        const ink     = token(s, "code-fg", token(s, "text", "#383838"));
        const ground  = token(s, "code-bg", token(s, "surface", "#ffffff"));
        const border  = token(s, "border", "#d8d8d8");
        const primary = token(s, "primary", "#7cafc2");
        const success = token(s, "success", "#16a34a");
        return {
            base00: ground,                              // the slab behind everything
            base01: token(s, "surface-alt", ground),     // toolbar and search fill
            base02: border,                              // rules, indent guides, null pill
            base03: token(s, "border-strong", border),   // hovered borders
            base04: token(s, "text-muted", ink),         // item counts
            base05: ink,                                 // plain text, undefined
            base06: token(s, "text-body", ink),
            base07: token(s, "text-strong", ink),        // keys, colons, brackets
            base08: token(s, "danger", "#dc2626"),       // NaN
            base09: token(s, "warning", "#d97706"),      // strings, ellipsis
            base0A: token(s, "text-subtle", ink),        // null, regexp
            base0B: success,                             // floats
            base0C: primary,                             // numeric keys
            base0D: primary,                             // icons, dates, open arrow
            base0E: primary,                             // booleans, closed arrow
            base0F: success,                             // integers
        };
    }

    /** The palette as it is written to the attribute, or null before first run. */
    let current = null;

    function dress(el) {
        if (current !== null && el.getAttribute("theme") !== current) {
            el.setAttribute("theme", current);
        }
    }

    /** Recomputes the palette and, if the theme moved, repaints every viewer. */
    function refresh() {
        const next = JSON.stringify(palette());
        if (next === current) return;
        current = next;
        document.querySelectorAll(TAG).forEach(dress);
    }

    /*
     * Two things to watch: the theme class on <html>, which the picker swaps,
     * and viewers arriving in a re-render, which come back with whatever
     * theme the server rendered. Dressing them straight from the observer
     * callback — rather than on a timeout, as the picker does — keeps the
     * default light slab from being painted for a frame first.
     */
    new MutationObserver(function (records) {
        for (const record of records) {
            if (record.type === "attributes") {
                refresh();
                continue;
            }
            for (const node of record.addedNodes) {
                if (node.nodeType !== Node.ELEMENT_NODE) continue;
                if (node.matches(TAG)) dress(node);
                node.querySelectorAll(TAG).forEach(dress);
            }
        }
    }).observe(document.documentElement, {
        childList: true,
        subtree: true,
        attributes: true,
        attributeFilter: ["class"],
    });

    refresh();
})();
