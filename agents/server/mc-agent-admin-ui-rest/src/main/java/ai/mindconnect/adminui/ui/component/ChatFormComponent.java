package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.adminui.ui.UiComponent;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.ui.model.UiPatch;

import java.util.UUID;

/**
 * The chat-input form at the bottom of a {@code ChatPage} — a single
 * textarea plus a context-sensitive Send / Stop button.
 *
 * <p>The component has two visual states:
 * <ul>
 *   <li><b>idle</b> — textarea is editable, button is a primary "Send" that
 *       posts to the streaming chat endpoint.</li>
 *   <li><b>streaming</b> — textarea is read-only, button is a danger "Stop"
 *       that cancels the in-flight turn via DELETE on the chat endpoint.</li>
 * </ul>
 *
 * <p>The form id is stable across both states so a REPLACE patch can
 * morph the body without losing the surrounding DOM context; the
 * textarea's id is also stable so {@code ignoreActiveValue} on the
 * morpher preserves anything the user has already typed.
 */
public final class ChatFormComponent implements UiComponent {

    private final UUID sessionId;
    private final UUID agentId;
    /**
     * Whether the chat turn is currently streaming when the page is rendered.
     * Drives {@link #render()} between the idle Send form and the streaming
     * Stop form so a navigate-back during a live turn lands the user in the
     * correct mode instead of showing a Send button that would interfere.
     */
    private final boolean streaming;

    public ChatFormComponent(UUID sessionId, UUID agentId) {
        this(sessionId, agentId, false);
    }

    public ChatFormComponent(UUID sessionId, UUID agentId, boolean streaming) {
        this.sessionId = sessionId;
        this.agentId = agentId;
        this.streaming = streaming;
    }

    @Override
    public String id() {
        return "chat-form-" + sessionId;
    }

    /** Renders the composer in whichever state matches the live stream registry. */
    @Override
    public ai.mindconnect.ui.model.UiNode render() {
        return streaming ? streamingForm() : idleForm();
    }

    // ── Patch operations ───────────────────────────────────────────────────

    /**
     * REPLACE that resets the form to its idle state (Send button,
     * editable textarea, no buffered text). Used after a turn completes
     * — both on the streaming and the non-streaming path.
     */
    public UiPatch.Operation reset() {
        return UiPatch.Operation.replace(id(), idleForm());
    }

    /**
     * REPLACE that swaps the Send button for a Stop button and locks the
     * textarea as read-only. Used at the start of a streaming turn.
     */
    public UiPatch.Operation toStreaming() {
        return UiPatch.Operation.replace(id(), streamingForm());
    }

    /** Alias for {@link #reset()}, named after the visual state for callers
     *  that prefer the {@code idle/streaming} vocabulary. */
    public UiPatch.Operation toIdle() {
        return reset();
    }

    // ── Internal builders ──────────────────────────────────────────────────

    private UiForm idleForm() {
        UiForm form = UiForm.of(id(), null)
                .field(UiField.textarea("message", "Message", null)
                        .asEditable().asRequired()
                        // Chat-style commit: Enter sends, Shift+Enter newline.
                        .submitOnEnter())
                // "+" opens the attach dialog (drop-zone lives there, not on
                // the page); the paper plane sends. Labels become the
                // accessible names, the sprite tokens the glyphs.
                .action(UiAction.icon("attach", "Attach files").icon("add")
                        .dispatch("GET", "/admin/api/sessions/" + sessionId + "/attach-dialog"))
                .action(UiAction.icon("send", "Send").icon("send")
                        .style(UiAction.Style.PRIMARY)
                        .stream("POST", "/admin/api/sessions/" + sessionId + "/chat/stream", id()));
        return form.<UiForm>withCssClass("chat-form");
    }

    /**
     * The composer's streaming state: the textarea gives way to a status
     * row — thinking indicator on the left, a prominent Stop button on the
     * right. Same node id as the idle form, so the two REPLACE into each
     * other. Cancel goes through the generic stream-registry endpoint keyed
     * by the same channelId the client uses for re-mount detection.
     */
    private ai.mindconnect.ui.model.UiNode streamingForm() {
        String channelId = "msg-list-" + sessionId;
        var row = ai.mindconnect.ui.model.UiStack.of(id());
        row.direction(ai.mindconnect.ui.model.UiStack.Direction.HORIZONTAL);
        row.withCssClass("chat-form chat-form--streaming");
        row.child(ai.mindconnect.ui.model.UiText.of(id() + ":thinking", "AI is thinking")
                .withCssClass("chat-thinking"));
        row.child(UiAction.danger("stop", "Stop").icon("stop")
                .dispatch("DELETE", "/admin/api/streams/" + channelId));
        return row;
    }
}
