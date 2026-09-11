package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.agentrest.service.LlmConfigTestService;
import ai.mindconnect.chatui.ui.UiComponent;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmConfigType;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiStack;
import ai.mindconnect.ui.model.UiText;

/**
 * Body of the "Test LLM config" modal dialog.
 *
 * <p>Renders in one of three shapes:
 * <ol>
 *   <li>Empty / initial — a textarea + Send button + Close button.</li>
 *   <li>After a successful test — the response text, token counts,
 *       finish reason, duration. The form stays mounted so the admin can
 *       re-send a tweaked message.</li>
 *   <li>After a failed test — the error message highlighted in red. The
 *       form stays mounted; "Send" tries again.</li>
 * </ol>
 *
 * <p>The form posts back to {@code POST /admin/api/llm-configs/{id}/test};
 * the controller re-renders the same dialog with the supplied
 * {@link LlmConfigTestService.Result}. The dialog overlay survives across
 * the round-trip because the controller re-opens it via
 * {@code page.dialog(...)} with the same close-href.
 */
public final class LlmConfigTestComponent implements UiComponent {

    private final LlmConfig config;
    private final LlmConfigType type;
    private final String previousMessage;
    private final LlmConfigTestService.Result result;

    /**
     * @param type what the config under test really is. For an alias that is
     *             the type of the config it points at — the test routes by
     *             name and lands there, so the dialog has to offer what that
     *             one needs (a recording for speech, a text for the rest).
     */
    public LlmConfigTestComponent(LlmConfig config,
                                   LlmConfigType type,
                                   String previousMessage,
                                   LlmConfigTestService.Result result) {
        this.config = config;
        this.type = type;
        this.previousMessage = previousMessage;
        this.result = result;
    }

    @Override
    public String id() { return "llm-test-" + config.id().value(); }

    public String title() { return "Test " + config.name(); }

    @Override
    public UiNode render() {
        if (type == LlmConfigType.SPEECH_TO_TEXT) {
            return renderTranscription();
        }
        boolean embedding = type == LlmConfigType.EMBEDDING;
        var form = UiForm.of(id(), null)
                .field(UiField.textarea("message", embedding ? "Text" : "Message",
                                previousMessage == null ? "" : previousMessage)
                        .asEditable().asRequired()
                        .hint(embedding
                                ? "Embedded into a vector — the result shows dimension and first values."
                                : "Sent as a single user-turn (no system prompt, no tools)."))
                .action(UiAction.primary("send", embedding ? "Embed" : "Send").icon("send")
                        .dispatch("POST", "/admin/api/llm-configs/" + config.id().value() + "/test", id()))
                // Close only removes the overlay (a tiny remove-patch) — never a
                // page reload, so the page state behind the dialog survives.
                .action(UiAction.secondary("close", "Close").icon("close")
                        .dispatch("POST", "/admin/api/llm-configs/test-dialog/close"));

        var stack = UiStack.of(id() + "-stack").child(form);
        if (result != null) stack.child(renderResult());
        return stack;
    }

    /**
     * The speech-to-text shape: a drop zone instead of a textarea. Picking or
     * dropping a recording posts it to the test endpoint as multipart, and the
     * transcript comes back in the same dialog. There is no Send button —
     * choosing the file is the action.
     */
    private UiNode renderTranscription() {
        var upload = ai.mindconnect.ui.model.UiUpload.of(id() + "-audio", "Recording")
                // Fixed part name — the node id carries the config's uuid.
                .name("audio")
                .accept("audio/*,video/webm,.webm,.wav,.mp3,.m4a,.ogg,.flac")
                .buttonLabel("Choose a recording")
                .dropText("Drop an audio file here or")
                .hint("Sent to the transcription endpoint as it is — the transcript comes back below. "
                        + "WebM, WAV, MP3, M4A, OGG and FLAC all work.")
                .uploadTo("/admin/api/llm-configs/" + config.id().value() + "/test-audio");

        // Speaking instead of uploading: the button's trigger runs in the
        // browser (INVOKE → the handler registered in audio-recorder.js),
        // which records, posts the recording to the same endpoint the drop
        // zone uses, and lets the bus apply the answer. A browser without a
        // microphone — or one that was denied it — says so in the status line
        // and the drop zone still works.
        var record = ai.mindconnect.ui.model.UiTrigger.invoke("mc-record-audio");
        record.setUrl("/admin/api/llm-configs/" + config.id().value() + "/test-audio");

        var actions = UiForm.of(id(), null)
                .action(UiAction.primary("record", "Record").icon("mic").onClick(record))
                .action(UiAction.secondary("close", "Close").icon("close")
                        .dispatch("POST", "/admin/api/llm-configs/test-dialog/close"));

        var stack = UiStack.of(id() + "-stack")
                .child(upload)
                .child(actions)
                .child(UiText.of(id() + "-record-status", "Or speak: Record starts, Stop transcribes.")
                        .<UiText>withCssClass("llm-record-status"));
        if (result != null) stack.child(renderResult());
        return stack;
    }

    /**
     * The tail of the success line. A transcription reports the recording it
     * heard rather than a finish reason, and reports token counts only when
     * the endpoint sent them — most speech endpoints bill by the minute.
     */
    private String metaTail() {
        boolean tokens = type != LlmConfigType.SPEECH_TO_TEXT
                || result.inputTokens() > 0 || result.outputTokens() > 0;
        String head = tokens
                ? " · " + result.inputTokens() + " in / " + result.outputTokens() + " out tokens"
                : "";
        if (result.finishReason().isEmpty()) return head;
        return head + " · " + (type == LlmConfigType.SPEECH_TO_TEXT
                ? result.finishReason()
                : "finish=" + result.finishReason());
    }

    private UiNode renderResult() {
        if (result.ok()) {
            String meta = "✓ OK · " + result.durationMs() + " ms" + metaTail();
            String body = result.text() == null || result.text().isEmpty()
                    ? "(empty response)"
                    : result.text();
            return UiStack.of(id() + "-ok")
                    .<UiStack>withCssClass("llm-test-result llm-test-result--ok")
                    .child(UiText.of(id() + "-meta", meta).<UiText>withCssClass("llm-test-meta"))
                    .child(UiText.of(id() + "-body", body).<UiText>withCssClass("llm-test-body"));
        }
        String meta = "✗ Failed · " + result.durationMs() + " ms";
        return UiStack.of(id() + "-err")
                .<UiStack>withCssClass("llm-test-result llm-test-result--err")
                .child(UiText.of(id() + "-meta", meta).<UiText>withCssClass("llm-test-meta"))
                .child(UiText.of(id() + "-body", result.errorMessage()).<UiText>withCssClass("llm-test-body"));
    }
}
