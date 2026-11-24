/**
 * The Speak button of a text-to-speech config's test dialog. Its trigger is
 * `INVOKE` with the handler registered here and names the test URL; the
 * payload is the dialog's form, so the text travels with it. A spoken answer
 * comes back as audio: it plays at once and the status line says how long it
 * took and what arrived. Anything else is the dialog with the error, returned
 * so the bus applies it.
 */
export function installSpeechTest(bus) {
    let player = null;

    bus.registerClientHandler("mc-test-speech", async (ctx) => {
        const status = document.querySelector(".llm-speech-status");
        const say = (text) => { if (status) status.textContent = text; };
        const message = (ctx.payload && ctx.payload.message) || "";
        if (!message.trim()) {
            say("Enter a text first.");
            return null;
        }
        say("Speaking …");
        const response = await ctx.fetch(ctx.url, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ message }),
        });
        const type = response.headers.get("content-type") || "";
        if (!response.ok) {
            say("The server answered " + response.status + ".");
            return null;
        }
        if (!type.startsWith("audio/")) return await response.json();

        const blob = await response.blob();
        if (player) player.pause();
        player = new Audio(URL.createObjectURL(blob));
        player.onended = () => URL.revokeObjectURL(player.src);
        say(response.headers.get("X-Mc-Test-Meta") || "OK");
        try {
            await player.play();
        } catch (e) {
            say("The audio arrived but the browser would not play it: " + e.message);
        }
        return null;
    });
}
