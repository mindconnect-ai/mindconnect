package ai.mindconnect.adminui.ui.page;

import ai.mindconnect.adminui.ui.AdminPage;
import ai.mindconnect.adminui.ui.component.ChatFormComponent;
import ai.mindconnect.adminui.ui.component.MessageListComponent;
import ai.mindconnect.adminui.ui.component.SessionInfoComponent;
import ai.mindconnect.adminui.ui.component.TaskCardComponent;
import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.memory.domain.WorkingMemory;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiPatch;
import ai.mindconnect.ui.model.UiSection;

import java.util.List;

/**
 * The agent-session chat page. Composes three components into a
 * tabbed section:
 * <ul>
 *   <li>{@link MessageListComponent} — the scrollable conversation
 *       (Chat tab), where almost all incremental updates land.</li>
 *   <li>{@link ChatFormComponent} — the input form below the
 *       conversation (also Chat tab).</li>
 *   <li>{@link SessionInfoComponent} — the Info tab with read-only
 *       session metadata.</li>
 * </ul>
 *
 * <p>Each page instance binds one session's domain model (session,
 * agent, history, memory) and exposes:
 * <ul>
 *   <li>{@link #render()} — the full page, returned on GET /sessions/{id}.</li>
 *   <li>One {@code UiPatch} method per lifecycle event of a chat turn —
 *       these are the only surface the {@code SessionUiController} needs
 *       to talk to during streaming, so the controller stays focused
 *       on orchestration.</li>
 * </ul>
 *
 * <p>The page is a short-lived value object: build it from the latest
 * model at the start of each request, derive whatever patch / page is
 * needed, throw it away. No fields beyond the components and their
 * model; no caches.
 */
public final class ChatPage extends AdminPage {

    private final AgentSession session;
    private final AgentDefinition agent;

    private final MessageListComponent messages;
    private final ChatFormComponent    chatForm;
    private final SessionInfoComponent info;

    public ChatPage(AgentSession session, AgentDefinition agent,
                    List<Message> history, WorkingMemory memory) {
        this(session, agent, history, memory, false);
    }

    public ChatPage(AgentSession session, AgentDefinition agent,
                    List<Message> history, WorkingMemory memory,
                    boolean streaming) {
        this(session, agent, history, memory, streaming,
                MessageListComponent.SubAgentTreeProvider.NONE);
    }

    /**
     * @param streaming    whether the agent is currently mid-turn. When true,
     *                     the chat form is rendered in Stop-button mode so a
     *                     navigate-back during a live turn doesn't show a
     *                     Send button the user can accidentally fire.
     * @param subAgentTree supplies the nested sub-agent card trees for
     *                     historic rendering (rebuilt from persisted child
     *                     sessions); {@link MessageListComponent.SubAgentTreeProvider#NONE}
     *                     for a flat fallback.
     */
    public ChatPage(AgentSession session, AgentDefinition agent,
                    List<Message> history, WorkingMemory memory,
                    boolean streaming,
                    MessageListComponent.SubAgentTreeProvider subAgentTree) {
        this.session = session;
        this.agent = agent;
        this.messages = new MessageListComponent(session.id(), agent, history, memory, subAgentTree)
                .withParentSession(session.parentSessionId());
        this.chatForm = new ChatFormComponent(session.id(), agent.id(), streaming);
        this.info     = new SessionInfoComponent(session, agent);
    }

    // ── Full render ────────────────────────────────────────────────────────

    /**
     * Server-known live streams to surface on this page, set by the
     * controller from the {@code ActiveStreams} registry. Stays null when
     * nothing is streaming — keeps the rendered JSON clean.
     */
    private java.util.List<UiPage.ActiveStream> activeStreams;

    /**
     * Lets the controller hand the chat-page the streams the SPA should
     * try to re-attach to (typically a single entry for this very
     * session's chat turn). Returns {@code this} for fluent chaining.
     */
    public ChatPage withActiveStreams(java.util.List<UiPage.ActiveStream> streams) {
        this.activeStreams = streams;
        return this;
    }

    /** The session's attached-files panel (built by SessionFileService). */
    private ai.mindconnect.ui.model.UiNode attachments;

    public ChatPage withAttachments(ai.mindconnect.ui.model.UiNode attachments) {
        this.attachments = attachments;
        return this;
    }

    /**
     * The open sub-agent approval cards of this (root) session — built by
     * the controller from the ToolApprovalStore, rendered by the message
     * list after the history. Returns {@code this} for fluent chaining.
     */
    public ChatPage withBubbledApprovals(List<ai.mindconnect.ui.model.UiList.Item> cards) {
        this.messages.withBubbledApprovals(cards);
        return this;
    }

    /**
     * The attach drop-zone — lives in the dialog the chat form's "+" button
     * opens (see SessionUiController#attachDialog), not on the page itself.
     * Files land in the session's vector store via the ingestion workflow,
     * and vector_search activates for the session.
     */
    public static ai.mindconnect.ui.model.UiUpload attachZone(java.util.UUID sessionId) {
        return ai.mindconnect.ui.model.UiUpload
                .of("chat-attach", null)
                .multiple()
                .dropText("Attach files (searchable by the agent)")
                .buttonLabel("Attach…")
                .uploadTo("/admin/api/sessions/" + sessionId + "/chat-files");
    }

    @Override
    public UiPage render() {
        // Title-less inner section stacks the Chat children without a tab bar
        // (see renderer); the outer section gives us the Chat/Info tabs.
        // Classic chat layout: the conversation takes the space between the
        // header and the composer (CSS: .chat-container flexes), attachment
        // chips sit just above the input, attach itself is the form's "+".

        // The attachments panel must always render (stable id) so attach/
        // delete patches have a target even before the first file arrives.
        var attachmentsPanel = attachments != null ? attachments
                : ai.mindconnect.ui.model.UiStack.of("chat-attachments");

        var chatPanel = UiSection.of("chat-panel-" + session.id(), null)
                .section("messages", null,
                        ai.mindconnect.ui.model.UiScrollPane
                                .of("chat-scroll-" + session.id(), messages.render())
                                .stickToLatest(true))
                .section("files",    null, attachmentsPanel)
                .section("input",    null, chatForm.render());

        var root = UiSection.of("session-" + session.id(), agent.name())
                .section("chat", "Chat", chatPanel)
                .section("info", "Info", info.render());

        var page = UiPage.of("/admin/sessions/" + session.id(), root);
        if (activeStreams != null && !activeStreams.isEmpty()) {
            page.setActiveStreams(activeStreams);
        }
        return page;
    }

    // ── Patch entry points ─────────────────────────────────────────────────

    /**
     * After a non-streaming {@code POST /chat}: refresh the conversation
     * (and its token-bar title) and reset the input form so the user can
     * type a follow-up.
     */
    public UiPatch chatTurnComplete() {
        return patch(messages.replaceAll(), chatForm.reset());
    }

    /**
     * Header-only refresh — used after a message deletion so the
     * token-bar in the title catches up without redoing the form.
     */
    public UiPatch headerOnly() {
        return patch(messages.replaceAll());
    }

    /**
     * Initial events of a streaming turn:
     * <ol>
     *   <li>Append the user's message immediately so they see it land.</li>
     *   <li>Swap the form to its streaming variant (Send → Stop).</li>
     *   <li>Append the "AI is thinking …" indicator.</li>
     * </ol>
     * Task cards from tool calls during the thinking phase land
     * <i>above</i> the indicator (lists append at the tail; the
     * indicator stays last). The indicator is removed on the first
     * incoming token via {@link #streamFirstToken(String, String)}.
     */
    /**
     * Streaming start for a RESUMED turn (approval answered): no user bubble
     * to append — the trigger was a card click, not typed text. Only the form
     * swaps to its streaming state (Send → Stop, thinking indicator).
     */
    /** APPEND a live approval card pushed by a suspended (sub-)turn. */
    public UiPatch appendApprovalCard(ai.mindconnect.ui.model.UiList.Item card) {
        return patch(messages.appendApprovalCard(card));
    }

    public UiPatch streamResume() {
        return patch(chatForm.toStreaming());
    }

    public UiPatch streamStart(String userText, String thinkingId) {
        // The thinking indicator lives in the composer's streaming state
        // (see ChatFormComponent#streamingForm) — nothing extra is appended
        // to the conversation, so the composer stays pinned in place.
        return patch(
                messages.appendUserMessage(userText),
                chatForm.toStreaming());
    }

    /**
     * First bot token arrived. Drop the thinking indicator and append
     * the streaming-reply placeholder. Subsequent tokens grow the
     * placeholder via {@link #streamToken(String, String)}.
     */
    public UiPatch streamFirstToken(String pendingId, String thinkingId) {
        // No thinking bubble to remove — the indicator sits in the composer
        // and disappears when the form resets at the end of the turn.
        return patch(messages.appendBotPending(pendingId, agent.name()));
    }

    /** Every subsequent token: grow the bot-pending body to the cumulative text. */
    public UiPatch streamToken(String pendingId, String cumulativeText) {
        return patch(messages.replaceBotPending(pendingId, cumulativeText));
    }

    /** APPEND a freshly-started task card (tool / sub-agent) to the list. */
    public UiPatch streamTaskStart(TaskCardComponent card) {
        return patch(messages.appendTaskCard(card));
    }

    /**
     * APPEND a freshly-started task card into a sub-agent's nested child
     * list ({@code childListId}) rather than the top-level conversation —
     * this is what builds the sub-agent tree live.
     */
    public UiPatch streamTaskStartInto(String childListId, TaskCardComponent card) {
        return patch(messages.appendTaskCardInto(childListId, card));
    }

    /** REPLACE a task card in place — used for done / failed transitions. */
    public UiPatch streamTaskUpdate(TaskCardComponent card) {
        return patch(messages.replaceTaskCard(card));
    }

    /**
     * Completes a sub-agent card without disturbing its nested child tree:
     * REPLACE the in-body status line in place, then APPEND the final-answer
     * markdown into the card's body stack. A wholesale card REPLACE would
     * morph the nested child list back to empty and wipe the already-streamed
     * children, so the two targeted ops are used instead.
     */
    public UiPatch streamSubAgentDone(ai.mindconnect.ui.model.UiNode statusNode,
                                      String stackId, ai.mindconnect.ui.model.UiNode answerNode) {
        return patch(
                UiPatch.Operation.replace(statusNode.getId(), statusNode),
                UiPatch.Operation.append(stackId, answerNode));
    }

    /**
     * Turn completed successfully. Reset the form back to its idle
     * variant and refresh the whole conversation so the streamed
     * bot-pending placeholder + live task cards get swapped for the
     * persisted assistant message and its historic task cards.
     */
    public UiPatch streamDone() {
        return patch(chatForm.reset(), messages.replaceAll());
    }

    /**
     * Streaming failed before completion. Only restore the form to
     * idle — the live partial state stays visible (the user gets a
     * separate error banner via the SSE 'error' event), and the next
     * page load will rebuild from persisted history.
     */
    public UiPatch streamError() {
        return patch(chatForm.reset());
    }

    /** Failure with a face: error bubble into the chat, form back to Send. */
    public UiPatch streamError(String message) {
        return patch(messages.appendErrorNotice(message), chatForm.reset());
    }
}
