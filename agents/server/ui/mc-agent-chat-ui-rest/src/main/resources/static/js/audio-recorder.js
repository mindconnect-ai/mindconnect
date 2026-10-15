/**
 * Speaking instead of typing, wherever a button asks for it.
 *
 * Two screens use this: the LLM-config test dialog of a speech-to-text config,
 * and the chat composer's microphone. Both carry a button whose trigger is
 * `INVOKE` with the handler registered here, and both name the URL the
 * recording goes to. The first click asks the browser for the microphone and
 * starts recording; the second stops it and posts the recording to that URL as
 * multipart/form-data under the part name `audio`, together with whatever the
 * trigger's payload node holds — the composer's draft text rides along that
 * way. The response is a UiPatch, returned from the handler so the bus applies
 * it: the dialog grows a transcript, the composer's textarea fills with one.
 *
 * The microphone needs a secure context: this works on localhost and over
 * HTTPS, and nowhere else. Recording holds the microphone until it is stopped,
 * so every exit path releases the tracks.
 */

/** Container formats a browser may hand us, and the file extension each needs. */
const FORMATS = [
    { mime: "audio/webm;codecs=opus", ext: "webm" },
    { mime: "audio/webm",             ext: "webm" },
    { mime: "audio/mp4",              ext: "m4a"  },  // Safari
    { mime: "audio/ogg;codecs=opus",  ext: "ogg"  },
];

function pickFormat() {
    if (typeof MediaRecorder === "undefined") return null;
    return FORMATS.find(f => MediaRecorder.isTypeSupported(f.mime)) || null;
}

/**
 * Where progress is written. The test dialog has a line of its own; the chat
 * composer has none and would not want one, so its own textarea says it in the
 * placeholder — visible, and gone again when the field is patched back.
 */
function statusTarget(ctx) {
    const line = document.querySelector(".llm-record-status");
    if (line) return { write: text => { line.textContent = text; }, restore: () => {} };

    const formId = ctx.trigger && ctx.trigger.payload;
    const area = formId ? document.querySelector("#" + CSS.escape(formId) + " textarea") : null;
    if (!area) return { write: () => {}, restore: () => {} };
    const original = area.placeholder;
    return {
        write: text => { area.placeholder = text || original; },
        restore: () => { area.placeholder = original; },
    };
}

/**
 * The button while it records. A labelled button says "Stop" — the framework
 * renders that label as a bare text node next to the icon, so the text node is
 * what changes; replacing the button's whole content would drop the icon. An
 * icon-only button has no text at all, and its accessible name carries the
 * state instead. Both get a class a stylesheet can pick up.
 */
function mark(button, recording) {
    button.classList.toggle("is-recording", recording);

    const text = [...button.childNodes].reverse()
        .find(node => node.nodeType === Node.TEXT_NODE && node.data.trim() !== "");
    if (text) {
        if (recording) {
            if (button.dataset.idleLabel === undefined) button.dataset.idleLabel = text.data;
            text.data = "Stop";
        } else if (button.dataset.idleLabel !== undefined) {
            text.data = button.dataset.idleLabel;
        }
        return;
    }
    // Icon-only: nothing to read but the accessible name.
    if (recording) {
        if (button.dataset.idleLabel === undefined) {
            button.dataset.idleLabel = button.getAttribute("aria-label") || "";
        }
        button.setAttribute("aria-label", "Stop recording");
        button.setAttribute("title", "Stop recording");
    } else if (button.dataset.idleLabel !== undefined) {
        button.setAttribute("aria-label", button.dataset.idleLabel);
        button.setAttribute("title", button.dataset.idleLabel);
    }
}

export function installAudioRecorder(bus) {
    // At most one recording at a time — a page shows one such button at once.
    let session = null;

    function release() {
        if (!session) return;
        clearInterval(session.timer);
        session.stream.getTracks().forEach(track => track.stop());
        mark(session.button, false);
        const status = session.status;
        session = null;
        return status;
    }

    async function start(ctx, button) {
        const status = statusTarget(ctx);
        // Browsers hand out the microphone only in a secure context: HTTPS,
        // or localhost. Over plain HTTP navigator.mediaDevices is not even
        // there, and the failure would otherwise read as "no microphone".
        if (!window.isSecureContext || !navigator.mediaDevices) {
            status.write("Recording needs HTTPS — this page is served over plain HTTP.");
            return;
        }
        const format = pickFormat();
        if (!format) {
            status.write("This browser cannot record audio.");
            return;
        }
        let stream;
        try {
            stream = await navigator.mediaDevices.getUserMedia({ audio: true });
        } catch (e) {
            status.write(e && e.name === "NotAllowedError"
                ? "The browser blocked the microphone."
                : "No microphone available.");
            return;
        }
        // From here the microphone is open. Anything that throws before the
        // session exists has to give it back, or the browser keeps showing a
        // recording indicator nobody can switch off.
        let recorder;
        const chunks = [];
        let stopped;
        try {
            recorder = new MediaRecorder(stream, { mimeType: format.mime });
            recorder.addEventListener("dataavailable", e => {
                if (e.data && e.data.size > 0) chunks.push(e.data);
            });
            stopped = new Promise(resolve => recorder.addEventListener("stop", resolve));
            recorder.start();
        } catch (e) {
            stream.getTracks().forEach(track => track.stop());
            status.write("This browser could not start the recorder.");
            console.error("MediaRecorder failed to start", e);
            return;
        }

        const startedAt = Date.now();
        session = { recorder, chunks, stream, button, format, stopped, status, timer: null };
        session.timer = setInterval(
            () => status.write("Recording… " + Math.round((Date.now() - startedAt) / 1000) + " s"),
            250);
        mark(button, true);
        status.write("Recording… 0 s");
    }

    async function stop(ctx) {
        const { recorder, chunks, format, stopped } = session;
        recorder.stop();
        await stopped;
        const status = release();

        const blob = new Blob(chunks, { type: format.mime });
        if (blob.size === 0) {
            status.write("Nothing was recorded.");
            return null;
        }
        status.write("Transcribing…");

        const body = new FormData();
        body.append("audio", blob, "recording." + format.ext);
        // Whatever the payload node holds travels with the recording — the
        // composer's draft, so the server can put the transcript after it.
        Object.entries(ctx.payload || {}).forEach(([key, value]) => {
            if (typeof value === "string" && value !== "") body.append(key, value);
        });

        const response = await ctx.fetch(ctx.url, { method: "POST", body });
        if (!response.ok) {
            status.write("The server answered " + response.status + ".");
            return null;
        }
        status.restore();
        return await response.json();
    }

    bus.registerClientHandler("mc-record-audio", async (ctx) => {
        if (session) {
            try {
                return await stop(ctx);
            } catch (e) {
                const status = release() || statusTarget(ctx);
                status.write("Recording failed.");
                console.error("Recording failed", e);
                return null;
            }
        }
        await start(ctx, ctx.sourceElement);
        return null;
    });
}
