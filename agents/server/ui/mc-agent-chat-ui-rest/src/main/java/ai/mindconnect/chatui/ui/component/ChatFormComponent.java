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
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AttachedFile;
import ai.mindconnect.agent.SessionId;

/**
 * The chat-input form at the bottom of a {@code ChatPage} — a single
 * textarea plus a context-sensitive Send / Stop button.
 *
 * <p>The component has two visual states, and the textarea is editable in
 * both:
 * <ul>
 *   <li><b>idle</b> — the full composer: "+", microphone, directory, model
 *       and a primary "Send" that posts to the chat endpoint.</li>
 *   <li><b>streaming</b> — the same form with the textarea still open, so the
 *       next message can be typed while the reply is being written; the
 *       footer holds "Stop", which cancels the running turn, and a disabled
 *       "Send". Sending while a turn runs is not supported yet, so nothing in
 *       this state starts a second turn — not the button, not Enter (see
 *       {@link #streamingForm()}).</li>
 * </ul>
 *
 * <p>The form id and the textarea are the same in both states. The switch
 * between them is a MERGE ({@link #toStreaming()}, {@link #toIdle()}) that
 * swaps the form's class, footer and extras but never its fields, so what the
 * user has typed survives it whether or not the textarea has focus — a
 * REPLACE only keeps the value of the focused control. {@link #reset()} is the
 * REPLACE that empties the field on purpose.
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
        return formId(sessionId);
    }

    /**
     * The composer's node id, without a component instance, for a control
     * outside the form that has to name the form it submits — naming it by
     * hand in two places is how the two drift apart.
     */
    public static String formId(SessionId sessionId) {
        return "chat-form-" + sessionId.value();
    }

    /** Renders the composer in whichever state matches the live stream registry. */
    @Override
    public ai.mindconnect.ui.model.UiNode render() {
        return streaming ? streamingForm() : idleForm();
    }

    /** How many documents and how many pictures hang on this conversation. */
    private int documentCount;
    private int imageCount;

    /**
     * Puts the file count on the "+" — so it is visible while typing — and the
     * document and picture counts on the menu's Upload files and Add images entries.
     */
    public ChatFormComponent withAttachments(java.util.List<AttachedFile> attachments) {
        this.imageCount = attachments == null ? 0
                : (int) attachments.stream().filter(AttachedFile::isImage).count();
        this.documentCount = attachments == null ? 0 : attachments.size() - imageCount;
        return this;
    }

    /** How many tools this chat offers up front, and how many agents it may call. */
    private int toolCount;
    private int subAgentCount;

    /**
     * Puts the tool and sub-agent counts on the "+" menu's entries, so what
     * the chat can reach for is readable from the composer rather than from a
     * dialog two clicks in. {@code agent} is the chat's effective agent.
     *
     * <p>The tool count is the Tools picker's "on" count, not every binding
     * the agent carries — see {@link ChatToolsPickerComponent#onCount}.
     *
     * @param offeredTools every tool name the registry can hand out here
     */
    public ChatFormComponent withAgentCounts(AgentDefinition agent,
                                             java.util.Collection<String> offeredTools) {
        this.toolCount = ChatToolsPickerComponent.onCount(agent, offeredTools);
        this.subAgentCount = agent == null ? 0 : agent.effectiveCallableAgents().size();
        return this;
    }

    private ChatPlusMenuComponent.Counts counts() {
        return new ChatPlusMenuComponent.Counts(documentCount, imageCount, toolCount, subAgentCount);
    }

    /**
     * The "+": a popover of everything a conversation can be given — files,
     * images, tools, sub-agents. It used to be a single button that opened the
     * attach dialog, which made files the only thing the composer could add;
     * see {@link ChatPlusMenuComponent} for why the rest moved in beside them.
     *
     * <p>It rides in the form's content rather than in the action footer,
     * because the footer renders {@code UiAction}s and a menu is a node. The
     * stylesheet parks it in the card's bottom-left corner, where the "+" has
     * always been.
     */
    private ai.mindconnect.ui.model.UiNode plusMenu() {
        return ChatPlusMenuComponent.menu(sessionId, counts());
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

    /** Whether users may choose the chat's directories here; without it there is no folder button. */
    private boolean dirChoice = true;

    /**
     * Shows the folder button only where users may choose a chat's
     * directories — on a shared server ({@code mindconnect.working-dirs.choice:
     * false}) every chat works in its own and there is nothing to choose.
     */
    public ChatFormComponent withDirChoice(boolean allowed) {
        this.dirChoice = allowed;
        return this;
    }

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
        String label = workingDir == null ? "Directory" : DirectoryPickerComponent.label(workingDir, sessionId);
        return UiAction.secondary("dir", label).icon("folder")
                .onClick(trigger(on(ChatUiController.class).dirDialog(sessionId, null)))
                .<UiAction>withCssClass("chat-model-btn");
    }

    // ── Patch operations ───────────────────────────────────────────────────

    /**
     * REPLACE that resets the form to its idle state with an EMPTY textarea.
     * For the one path that submitted the text and waited for the answer
     * (the non-streaming {@code POST /chat}); a streaming turn ends with
     * {@link #toIdle()}, which keeps whatever was typed meanwhile.
     */
    public UiPatch.Operation reset() {
        return UiPatch.Operation.replace(id(), idleForm());
    }

    /**
     * MERGE into the streaming state: Stop and a disabled Send in the
     * footer, the idle extras gone. The fields are not part of the merge, so
     * the textarea stays open and keeps its text and its focus. Used at the
     * start of a streaming turn.
     */
    public UiPatch.Operation toStreaming() {
        return mergeInto(streamingForm());
    }

    /**
     * MERGE back into the idle state at the end of a streaming turn. Like
     * {@link #toStreaming()} it leaves the fields alone, so a message typed
     * during the turn is still in the textarea afterwards, ready to send.
     */
    public UiPatch.Operation toIdle() {
        return mergeInto(idleForm());
    }

    /**
     * A MERGE that turns the rendered form into {@code target}, fields
     * excepted. Every other part the two states differ in is named, empty
     * lists included — a merge only writes what it carries, so a part left
     * out would keep the other state's value.
     *
     * <p>The nodes go in as JSON trees, each written as a node of its own.
     * The attributes are a {@code Map<String, Object>}, and a node that is
     * only an {@code Object} to Jackson is written without its {@code type}
     * — the client then has no renderer for it and dumps it as JSON.
     */
    private UiPatch.Operation mergeInto(UiForm target) {
        var attributes = new java.util.LinkedHashMap<String, Object>();
        attributes.put("cssClass", target.getCssClass());
        attributes.put("content", trees(target.getContent()));
        attributes.put("actions", trees(target.getActions()));
        return UiPatch.Operation.merge(id(), attributes);
    }

    private static java.util.List<com.fasterxml.jackson.databind.JsonNode> trees(
            java.util.List<? extends ai.mindconnect.ui.model.UiNode> nodes) {
        if (nodes == null) return java.util.List.of();
        return nodes.stream()
                .map(n -> (com.fasterxml.jackson.databind.JsonNode)
                        ai.mindconnect.chatui.ui.SessionUiCommons.MAPPER.valueToTree(n))
                .toList();
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
                // "+" opens the add-menu (files, images, tools, sub-agents);
                // the paper plane sends. Labels become the accessible names,
                // the sprite tokens the glyphs.
                .content(plusMenu())
                .action(recordAction());
        var countBadge = ChatPlusMenuComponent.fileCount(sessionId, counts());
        if (countBadge != null) {
            form = form.content(countBadge);
        }
        if (dirChoice) {
            form = form.action(dirAction());
        }
        form = form
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
     * The composer's streaming state: the same form and the same textarea as
     * the idle one, so typing goes on while the reply is written. What
     * changes is the footer: a prominent Stop, which cancels the turn through
     * the generic stream-registry endpoint keyed by the channelId the client
     * uses for re-mount detection, and Send — disabled and without a trigger.
     *
     * <p>That Send is also what keeps Enter from doing anything. The textarea
     * still submits on Enter, and a submitted form fires its primary action;
     * a Send with nothing to fire makes that a no-op, and the text stays
     * where it is. Without a primary action the form would pick the first
     * action it has — Stop.
     *
     * <p>The "+", the microphone, the directory and the model are left out as
     * before: nothing about the chat changes while a turn runs.
     */
    private UiForm streamingForm() {
        String channelId = ai.mindconnect.chatui.service.SessionOwnership.channelOf(sessionId);
        return UiForm.of(id(), null)
                .field(messageField(null))
                .action(UiAction.icon("stop", "Stop").icon("stop")
                        .style(UiAction.Style.DANGER)
                        .onClick(trigger(on(StreamController.class).cancel(channelId, null))))
                .action(UiAction.icon("send", "Send").icon("send")
                        .style(UiAction.Style.PRIMARY)
                        .disabled("Send is available again when the reply is finished"))
                .<UiForm>withCssClass("chat-form chat-form--streaming");
    }
}
