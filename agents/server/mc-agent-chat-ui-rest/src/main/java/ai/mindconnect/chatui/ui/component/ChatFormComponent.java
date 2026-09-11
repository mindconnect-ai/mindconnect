package ai.mindconnect.chatui.ui.component;

import ai.mindconnect.chatui.ui.UiComponent;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.chatui.ui.controller.ChatUiController;

import static ai.mindconnect.ui.mvc.UiActions.streaming;
import static ai.mindconnect.ui.mvc.UiActions.trigger;
import static org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder.on;
import ai.mindconnect.chatui.ui.controller.StreamController;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.ui.model.UiPatch;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;

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

    private final SessionId sessionId;
    private final AgentId agentId;
    /**
     * Whether the chat turn is currently streaming when the page is rendered.
     * Drives {@link #render()} between the idle Send form and the streaming
     * Stop form so a navigate-back during a live turn lands the user in the
     * correct mode instead of showing a Send button that would interfere.
     */
    private final boolean streaming;

    public ChatFormComponent(SessionId sessionId, AgentId agentId) {
        this(sessionId, agentId, false);
    }

    public ChatFormComponent(SessionId sessionId, AgentId agentId, boolean streaming) {
        this.sessionId = sessionId;
        this.agentId = agentId;
        this.streaming = streaming;
    }

    @Override
    public String id() {
        return "chat-form-" + sessionId.value();
    }

    /** Renders the composer in whichever state matches the live stream registry. */
    @Override
    public ai.mindconnect.ui.model.UiNode render() {
        return streaming ? streamingForm() : idleForm();
    }

    /** How many files hang on this conversation — shown on the "+". */
    private int attachmentCount;

    /** Puts the file count on the attach button, so it is visible while typing. */
    public ChatFormComponent withAttachmentCount(int count) {
        this.attachmentCount = Math.max(count, 0);
        return this;
    }

    /**
     * The "+": an icon on its own while nothing is attached, an icon with a
     * count once something is. The files themselves live behind it, in the
     * dialog it opens — a strip above the input pushed the conversation up
     * for something you only look at when you go looking.
     */
    private UiAction attachAction() {
        var open = trigger(on(ChatUiController.class).attachDialog(sessionId.value(), null));
        if (attachmentCount == 0) {
            return UiAction.icon("attach", "Attach files").icon("add")
                    .onClick(open);
        }
        return UiAction.secondary("attach", "(" + attachmentCount + ")").icon("add")
                .onClick(open)
                .<UiAction>withCssClass("chat-attach-btn");
    }

    /** What the composer's model button says — the chat's current model. */
    private String modelLabel = "Model & tools";

    /** Shows the running model on the composer button. */
    public ChatFormComponent withModelLabel(String llmConfigName) {
        if (llmConfigName != null && !llmConfigName.isBlank()) {
            this.modelLabel = llmConfigName;
        }
        return this;
    }

    /** The chat's working directory, or {@code null} for the server's default. */
    private String workingDir;

    /** Names the working directory on the composer's folder button. */
    public ChatFormComponent withWorkingDir(String workingDir) {
        this.workingDir = workingDir == null || workingDir.isBlank() ? null : workingDir;
        return this;
    }

    /**
     * The folder button: the working directory's own name, or a plain
     * "Directory" while the chat has none — it opens the chooser. Sits
     * beside the model, since where the chat works is as much its setting
     * as what it runs on.
     */
    private UiAction dirAction() {
        String label = workingDir == null ? "Directory" : DirectoryPickerComponent.name(workingDir);
        return UiAction.secondary("dir", label).icon("folder")
                .onClick(trigger(on(ChatUiController.class).dirDialog(sessionId, null)))
                .<UiAction>withCssClass("chat-model-btn");
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

    /**
     * The composer's textarea. Built here so anything that refills it —
     * dictation puts the transcript in — patches in the same field rather
     * than a lookalike: same id, same placeholder, same Enter-sends.
     *
     * @param value what the field starts with; {@code null} for empty
     */
    public static UiField messageField(String value) {
        return UiField.textarea("message", "Message", value)
                .asEditable().asRequired()
                // The label stays for the accessible name; the placeholder is
                // what the composer shows, so the field reads as an invitation
                // rather than as a labelled form control.
                .placeholder("Ask anything \u2026")
                // Chat-style commit: Enter sends, Shift+Enter newline.
                .submitOnEnter();
    }

    private UiForm idleForm() {
        UiForm form = UiForm.of(id(), null)
                .field(messageField(null))
                // "+" opens the attach dialog (drop-zone lives there, not on
                // the page); the paper plane sends. Labels become the
                // accessible names, the sprite tokens the glyphs.
                .action(attachAction())
                .action(recordAction())
                .action(dirAction())
                // Model and tools sit on the composer, where you notice them
                // while typing — not in a settings page you have to go find.
                .action(UiAction.secondary("model", modelLabel).icon("ai")
                        .onClick(trigger(on(ChatUiController.class).settingsDialog(sessionId.value(), null)))
                        .<UiAction>withCssClass("chat-model-btn"))
                .action(UiAction.icon("send", "Send").icon("send")
                        .style(UiAction.Style.PRIMARY)
                        // A plain dispatch, not a stream: the turn's output
                        // comes back on the session stream this client already
                        // reads, the same one every other client of the session
                        // reads.
                        .onClick(trigger(on(ChatUiController.class).chatStream(sessionId.value(), null, null), id())));
        return form.<UiForm>withCssClass("chat-form");
    }

    /**
     * The microphone. Its trigger runs in the browser — the handler
     * registered in {@code audio-recorder.js} records, posts the recording to
     * the session's transcribe endpoint and applies the patch that comes
     * back, which is this same field with the transcript in it. The form id
     * travels as the payload so the recording carries whatever was already
     * typed, and so the handler knows which textarea to talk to while it
     * records.
     *
     * <p>A browser with no microphone, or one that was denied it, says so in
     * the composer's placeholder and nothing else happens; typing is
     * untouched either way.
     */
    private UiAction recordAction() {
        var trigger = ai.mindconnect.ui.model.UiTrigger.invoke("mc-record-audio", id());
        trigger.setUrl("/chat/api/sessions/" + sessionId.value() + "/voice/transcribe");
        return UiAction.icon("record", "Dictate").icon("mic")
                .onClick(trigger)
                .<UiAction>withCssClass("chat-record-btn");
    }

    /**
     * The composer's streaming state: the textarea gives way to a status
     * row — thinking indicator on the left, a prominent Stop button on the
     * right. Same node id as the idle form, so the two REPLACE into each
     * other. Cancel goes through the generic stream-registry endpoint keyed
     * by the same channelId the client uses for re-mount detection.
     */
    private ai.mindconnect.ui.model.UiNode streamingForm() {
        String channelId = ai.mindconnect.chatui.service.SessionOwnership.channelOf(sessionId);
        var row = ai.mindconnect.ui.model.UiStack.of(id());
        row.direction(ai.mindconnect.ui.model.UiStack.Direction.HORIZONTAL);
        row.withCssClass("chat-form chat-form--streaming");
        row.child(ai.mindconnect.ui.model.UiText.of(id() + ":thinking", "AI is thinking")
                .withCssClass("chat-thinking"));
        row.child(UiAction.danger("stop", "Stop").icon("stop")
                .onClick(trigger(on(StreamController.class).cancel(channelId, null))));
        return row;
    }
}
