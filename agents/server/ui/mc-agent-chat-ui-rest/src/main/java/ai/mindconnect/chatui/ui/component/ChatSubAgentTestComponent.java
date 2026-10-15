package ai.mindconnect.chatui.ui.component;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.chatui.ui.controller.ChatUiController;
import ai.mindconnect.ui.ext.markdown.UiMarkdown;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiStack;
import ai.mindconnect.ui.model.UiText;

import static ai.mindconnect.ui.mvc.UiActions.trigger;
import static org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder.on;

/**
 * Body of the "Test &lt;agent&gt;" dialog behind a sub-agent's Test button: a
 * message, Send, and — once sent — what the agent answered or why it did not.
 *
 * <p>The agent runs as itself, with its own prompt, model and tools, in a
 * throwaway session that is gone again when the answer is back. The form
 * stays mounted under the answer, so a tweaked message is one Send away.
 */
public class ChatSubAgentTestComponent {

    /** The answer of one test run: the agent's text, or what went wrong. */
    public record Result(boolean ok, String text, long durationMs) {

        public static Result answered(String text, long durationMs) {
            return new Result(true, text, durationMs);
        }

        public static Result failed(String message, long durationMs) {
            return new Result(false, message, durationMs);
        }
    }

    /** Stable id of the dialog's body, so Send can REPLACE it in place. */
    public static final String BODY_ID = "chat-subagent-test-body";

    public static String formId(SessionId sessionId) {
        return "chat-subagent-test-" + sessionId.value();
    }

    /**
     * @param message the message last sent, kept in the field; {@code null} for none
     * @param result  the answer to it; {@code null} before the first Send
     */
    public static UiNode node(SessionId sessionId, AgentDefinition agent, String message, Result result) {
        var form = UiForm.of(formId(sessionId), null)
                .field(UiField.textarea("message", "Message", message == null ? "" : message)
                        .asEditable().asRequired()
                        .hint("Sent to " + agent.name() + " as a task of its own — its prompt, its "
                                + "model, its tools. This chat does not see it."))
                .action(UiAction.primary("send", "Send").icon("send")
                        .onClick(trigger(on(ChatUiController.class)
                                        .testSubAgent(sessionId.value(), agent.name(), null, null),
                                formId(sessionId))))
                .action(UiAction.secondary("back", "Back").icon("arrow-left")
                        .onClick(trigger(on(ChatUiController.class)
                                .subAgentsDialog(sessionId.value(), null))));

        var body = UiStack.of(BODY_ID).gap(12).child(form);
        if (result != null) {
            body.child(result(result));
        }
        return body;
    }

    private static UiNode result(Result result) {
        String meta = (result.ok() ? "✓ Answered" : "✗ Failed") + " · " + result.durationMs() + " ms";
        String text = result.text() == null || result.text().isBlank()
                ? (result.ok() ? "(empty answer)" : "(no message)")
                : result.text();
        return UiStack.of(BODY_ID + "-result")
                .<UiStack>withCssClass("chat-subagent-test-result "
                        + (result.ok() ? "chat-subagent-test-result--ok" : "chat-subagent-test-result--err"))
                .child(UiText.of(BODY_ID + "-meta", meta).<UiText>withCssClass("chat-picker-hint"))
                .child(result.ok()
                        ? UiMarkdown.of(BODY_ID + "-answer", MarkdownText.safe(text))
                        : UiText.of(BODY_ID + "-answer", text));
    }
}
