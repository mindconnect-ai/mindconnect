// "Copy" beside a field: puts the field's value on the clipboard.
//
// semantic-ui has no clipboard behaviour, so the server gives the field a
// trailing action with UiTrigger.invoke("mc-copy-field") and this handler does
// the rest in the browser. It copies the input of the field the clicked button
// belongs to — no id to pass — and selects the text as well, so the value is
// still at hand where the clipboard API is not available (plain http on a
// host other than localhost).

/** Registers the "mc-copy-field" client handler on the event bus. */
export function installCopyField(bus) {
    bus.registerClientHandler("mc-copy-field", async (ctx) => {
        const input = ctx.sourceElement?.closest(".sui-field")?.querySelector("input, textarea");
        if (!input) {
            console.warn("mc-copy-field: no field around the clicked element", ctx.sourceElement);
            return null;
        }
        input.focus();
        input.select();

        let copied = false;
        if (navigator.clipboard && window.isSecureContext) {
            try {
                await navigator.clipboard.writeText(input.value);
                copied = true;
            } catch {
                // denied or unavailable: fall back below
            }
        }
        if (!copied) {
            try {
                copied = document.execCommand("copy");
            } catch {
                copied = false;
            }
        }

        return {
            patches: [],
            toasts: [copied
                ? { level: "SUCCESS", title: "Copied", message: "The value is on the clipboard.", durationMs: 2500 }
                : { level: "WARN", title: "Not copied", message: "The browser refused — the value is selected, copy it with Ctrl+C / ⌘C.", durationMs: 5000 }],
        };
    });
}
