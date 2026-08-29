package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.agentrest.service.LlmConfigTestService;
import ai.mindconnect.chatui.ui.UiComponent;
import ai.mindconnect.llm.domain.LlmConfig;
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
    private final String previousMessage;
    private final LlmConfigTestService.Result result;

    public LlmConfigTestComponent(LlmConfig config,
                                   String previousMessage,
                                   LlmConfigTestService.Result result) {
        this.config = config;
        this.previousMessage = previousMessage;
        this.result = result;
    }

    @Override
    public String id() { return "llm-test-" + config.id(); }

    public String title() { return "Test " + config.name(); }

    @Override
    public UiNode render() {
        boolean embedding = config.isEmbedding();
        var form = UiForm.of(id(), null)
                .field(UiField.textarea("message", embedding ? "Text" : "Message",
                                previousMessage == null ? "" : previousMessage)
                        .asEditable().asRequired()
                        .hint(embedding
                                ? "Embedded into a vector — the result shows dimension and first values."
                                : "Sent as a single user-turn (no system prompt, no tools)."))
                .action(UiAction.primary("send", embedding ? "Embed" : "Send").icon("send")
                        .dispatch("POST", "/admin/api/llm-configs/" + config.id() + "/test", id()))
                // Close only removes the overlay (a tiny remove-patch) — never a
                // page reload, so the page state behind the dialog survives.
                .action(UiAction.secondary("close", "Close").icon("close")
                        .dispatch("POST", "/admin/api/llm-configs/test-dialog/close"));

        var stack = UiStack.of(id() + "-stack").child(form);
        if (result != null) stack.child(renderResult());
        return stack;
    }

    private UiNode renderResult() {
        if (result.ok()) {
            String meta = "✓ OK · " + result.durationMs() + " ms · "
                    + result.inputTokens() + " in / " + result.outputTokens() + " out tokens"
                    + (result.finishReason().isEmpty() ? "" : " · finish=" + result.finishReason());
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
