package ai.mindconnect.chatui.ui.component;

import ai.mindconnect.chatui.ui.UiComponent;
import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.memory.domain.WorkingMemory;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageType;
import ai.mindconnect.message.domain.ParticipantType;
import ai.mindconnect.ui.ext.markdown.UiMarkdown;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiList;
import ai.mindconnect.ui.model.UiPatch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static ai.mindconnect.chatui.ui.SessionUiCommons.DT_FMT;
import static ai.mindconnect.chatui.ui.SessionUiCommons.MAPPER;

/**
 * The main conversation list of a chat page. Renders the message
 * history (interleaved with historic task cards rebuilt from
 * persisted TOOL_CALL/TOOL_RESULT messages) and exposes both the
 * navigation action buttons (Back / Memory / Traces / Todos /
 * Workspace) and the token-usage suffix in its title.
 *
 * <p>The component is the heart of the chat page in patch terms:
 * almost every streaming event translates into a patch against either
 * the list's root id ({@code msg-list-{sid}}) — for full refreshes
 * after a turn — or against individual item ids inside the list
 * (bot-pending placeholder, task cards, thinking indicator).
 *
 * <p>Patch shapes follow the renderer contract:
 * <ul>
 *   <li>APPEND with a single-item wrapper {@code UiList} — the
 *       renderer extracts the wrapper's {@code items} into the
 *       existing {@code <ul>} so the list header isn't duplicated.</li>
 *   <li>REPLACE with the rendered {@link UiList} of the entire
 *       component — used when the header (token bar) needs to refresh
 *       alongside body changes.</li>
 *   <li>REMOVE on a wrapper id — drops the surrounding {@code <li>}.</li>
 * </ul>
 */
public final class MessageListComponent implements UiComponent {

    /**
     * Supplies the pre-built sub-agent cards (with their nested child-task
     * trees) for a given parent {@code toolCallId}. Implemented by the
     * controller, which has the session repositories needed to walk the
     * call tree and load each sub-session's history. Returns an empty list
     * when the call spawned no sub-sessions (or sub-agent rendering is
     * disabled — the {@link #NONE} default).
     */
    @FunctionalInterface
    public interface SubAgentTreeProvider {
        /**
         * @param parentToolCallId the parent's tool-call id that spawned the sub-agent(s)
         * @param running          true when the parent has no persisted TOOL_RESULT for
         *                         this call yet — i.e. the sub-agent(s) are still in
         *                         flight (page loaded mid-turn). Drives the running
         *                         marker; the child session's own status is unreliable
         *                         (sessions are never transitioned out of ACTIVE on disk).
         * @param inputJson        the {@code run_agent(s)} call's pretty-printed input
         *                         arguments (the task message etc.), or null. Shown in
         *                         the card so a sub-agent reads like a normal tool call.
         * @param resultText       the persisted tool-result text (the sub-agent's
         *                         answer the parent received), or null while running.
         */
        List<TaskCardComponent> cardsFor(String parentToolCallId, boolean running,
                                         String inputJson, String resultText);

        /** No sub-agent tree available — historic sub-agent calls fall back to a flat card. */
        SubAgentTreeProvider NONE = (id, running, in, out) -> List.of();
    }

    private final UUID sessionId;
    private final AgentDefinition agent;
    private final List<Message> history;
    private final WorkingMemory memory;
    private final SubAgentTreeProvider subAgentTree;
    /** Set when this session was spawned by a parent (sub-agent session) — drives the header link. */
    private UUID parentSessionId;
    /** Bubbled sub-agent approval cards (from the ToolApprovalStore), rendered after the history. */
    private List<UiList.Item> bubbledApprovalCards = List.of();



    public MessageListComponent(UUID sessionId, AgentDefinition agent,
                                List<Message> history, WorkingMemory memory) {
        this(sessionId, agent, history, memory, SubAgentTreeProvider.NONE);
    }

    public MessageListComponent(UUID sessionId, AgentDefinition agent,
                                List<Message> history, WorkingMemory memory,
                                SubAgentTreeProvider subAgentTree) {
        this.sessionId = sessionId;
        this.agent = agent;
        this.history = history;
        this.memory = memory;
        this.subAgentTree = subAgentTree != null ? subAgentTree : SubAgentTreeProvider.NONE;
    }

    /**
     * Sets the parent session id so the header shows an "↑ Parent Session"
     * link. Pass {@code null} (the default) for top-level, user-initiated
     * sessions. Returns {@code this} for fluent chaining.
     */
    public MessageListComponent withParentSession(UUID parentSessionId) {
        this.parentSessionId = parentSessionId;
        return this;
    }

    /**
     * Cards for the OPEN sub-agent approval questions of this (root) session
     * — the controller builds them from the {@code ToolApprovalStore}, the
     * single truth for bubbled requests (nothing about them lives in this
     * conversation's history). Returns {@code this} for fluent chaining.
     */
    public MessageListComponent withBubbledApprovals(List<UiList.Item> cards) {
        this.bubbledApprovalCards = cards != null ? cards : List.of();
        return this;
    }

    @Override
    public String id() {
        return "msg-list-" + sessionId;
    }

    @Override
    public UiList render() {
        // The conversation's own header, and the only one on the page. It used
        // to be empty because the chat rendered a second app-shell header
        // above it that named the agent — two title bars where every other
        // screen has one, which is what made the chat look like it came from
        // somewhere else. That header is gone; this one names the agent, the
        // way "Agents" names the agents list.
        var list = UiList.of(id(), agent == null ? "Chat" : agent.name());
        // The icon leads the title, the way every other screen's does.
        list.icon("chat");
        list.withCssClass("chat-container");
        // The history is a drawer now, opened from here. chat-ui.js owns the
        // click: the drawer is client state, and the server has no opinion
        // about whether a sidebar happens to be open.
        // No cssClass: an icon action does not carry one through to the
        // button. The id is what the stylesheet and chat-ui.js hold on to.
        list.action(ai.mindconnect.ui.model.UiAction.icon("chat-history", "Chats")
                .icon("menu"));
        list.headerExtra(new TokenUsageComponent(id(), memory).render());

        // Sort by sequenceNum to be safe — persisted ordering should already
        // be correct but the component does not trust upstream.
        List<Message> sorted = history.stream()
                .sorted(Comparator.comparingInt(Message::sequenceNum))
                .toList();

        List<Message> chatMessages = sorted.stream()
                .filter(m -> m.type() == MessageType.CHAT)
                .toList();

        if (chatMessages.isEmpty()) {
            addWelcomeOrEmpty(list);
            return list;
        }

        // Group tool/task messages between consecutive agent CHAT messages.
        // For each agent CHAT, render all TOOL_CALL/TOOL_RESULT messages
        // that came after the previous CHAT and before this one as their
        // own collapsible task card BEFORE the agent message — so the flow
        // reads top-to-bottom (user → tools → answer), Claude-style.
        int prevAgentSeq = -1;
        for (Message m : chatMessages) {
            boolean isUser = m.senderType() == ParticipantType.USER;
            if (!isUser) {
                List<TaskCardComponent> tasks = toolCallHistory().buildHistoricTaskCards(sorted, prevAgentSeq, m.sequenceNum());
                for (TaskCardComponent t : tasks) {
                    // Reuse the wrapper render to get the same <li> shape.
                    list.item(((UiList) t.render()).getItems().get(0));
                }
            }
            list.item(messageItem(m, isUser));
            prevAgentSeq = m.sequenceNum();
        }
        // The IN-FLIGHT turn's activity: task messages after the last CHAT
        // have no agent answer to group under yet — render them anyway, or a
        // mid-turn rebuild (approval answered, page reloaded) would make the
        // running tool and sub-agent cards vanish until the turn ends.
        for (TaskCardComponent t : toolCallHistory().buildHistoricTaskCards(sorted, prevAgentSeq, Integer.MAX_VALUE)) {
            list.item(((UiList) t.render()).getItems().get(0));
        }
        // Open approval questions live ONLY in the ToolApprovalStore now
        // (the gate parks the tool task and registers the card there) — the
        // controller hands them in, rendered after the history.
        bubbledApprovalCards.forEach(list::item);
        return list;
    }


    /**
     * APPEND a visible failure notice — a turn that FAILED (context overflow,
     * backend error) must say so in the chat, not just stop the spinner. Not
     * persisted: the failure lives on the task record; this is the live face
     * of it.
     */
    public UiPatch.Operation appendErrorNotice(String message) {
        String id = "turn-error-" + System.nanoTime();
        var wrapper = UiList.of(id + "-wrapper", null);
        wrapper.item(UiList.Item.of(id, "Error")
                .content(UiMarkdown.of(id + "-md",
                                "⚠️ **The turn failed:** " + message)
                        .withCssClass("bot-message error-message")));
        return UiPatch.Operation.append(id(), wrapper);
    }

    /** APPEND the live approval card — the stream-side twin of the historic render. */
    public UiPatch.Operation appendApprovalCard(UiList.Item card) {
        var wrapper = UiList.of("approval-wrapper-" + card.getId(), null);
        wrapper.item(card);
        return UiPatch.Operation.append(id(), wrapper);
    }

    // ── Patch operations ───────────────────────────────────────────────────

    /**
     * REPLACE on the list root. Used after a turn finishes (streaming or
     * non-streaming): rebuilds the full list from persisted history so the
     * live bot-pending placeholder and live task cards get swapped for
     * the final assistant message and its historic task cards. The token
     * bar in the title refreshes at the same time.
     */
    public UiPatch.Operation replaceAll() {
        return UiPatch.Operation.replace(id(), render());
    }

    /**
     * APPEND a synthetic user-message item. The id is temporary (epoch
     * milliseconds) and only matters until the next {@link #replaceAll()}
     * — which is fine because the streaming chat always replaces the
     * whole list at the end of the turn.
     */
    public UiPatch.Operation appendUserMessage(String text) {
        String tempId = "user-msg-" + System.currentTimeMillis();
        String time = DT_FMT.format(java.time.Instant.now());
        var wrapper = UiList.of("user-item-" + tempId, null);
        wrapper.item(UiList.Item.of(tempId, "You  [" + time + "]")
                .content(UiMarkdown.of(tempId + "-md", text)
                        .<UiMarkdown>withCssClass("user-message")));
        return UiPatch.Operation.append(id(), wrapper);
    }

    /**
     * APPEND the bot-pending placeholder used as the streaming target.
     * Subsequent token deltas REPLACE the placeholder's body (via
     * {@link #replaceBotPending(String, String)}).
     */
    public UiPatch.Operation appendBotPending(String pendingId, String agentName) {
        String time = DT_FMT.format(java.time.Instant.now());
        var wrapper = UiList.of("bot-pending-wrapper", null);
        wrapper.item(UiList.Item.of(pendingId, agentName + "  [" + time + "]")
                .content(UiMarkdown.of(pendingId, "…")
                        .<UiMarkdown>withCssClass("bot-message")));
        return UiPatch.Operation.append(id(), wrapper);
    }

    /**
     * REPLACE the bot-pending markdown body with the current cumulative
     * text. The pendingId is the same id used by
     * {@link #appendBotPending(String, String)}.
     */
    public UiPatch.Operation replaceBotPending(String pendingId, String cumulativeText) {
        return UiPatch.Operation.replace(pendingId,
                UiMarkdown.of(pendingId, cumulativeText)
                        .<UiMarkdown>withCssClass("bot-message"));
    }

    /**
     * APPEND an "AI is thinking …" placeholder shown between the user
     * message and the first incoming token. Removed via
     * {@link #removeThinking(String)} when the stream begins.
     */
    public UiPatch.Operation appendThinking(String thinkingId, String agentName) {
        var wrapper = UiList.of("thinking-wrapper-" + thinkingId, null);
        wrapper.item(UiList.Item.of(thinkingId, agentName)
                .content(UiMarkdown.of(thinkingId + "-md", "AI is thinking")
                        .<UiMarkdown>withCssClass("bot-message bot-message--thinking")));
        return UiPatch.Operation.append(id(), wrapper);
    }

    /**
     * REMOVE the thinking indicator together with its wrapping {@code <li>}.
     * Targets the wrapper id ({@code thinking-wrapper-{thinkingId}}) so
     * the renderer's REMOVE path drops the surrounding list item.
     */
    public UiPatch.Operation removeThinking(String thinkingId) {
        return UiPatch.Operation.remove("thinking-wrapper-" + thinkingId);
    }

    /** APPEND a task card to the list (running / done / failed variants). */
    public UiPatch.Operation appendTaskCard(TaskCardComponent card) {
        return UiPatch.Operation.append(id(), card.render());
    }

    /**
     * APPEND a task card into a nested sub-agent child list rather than the
     * top-level message list. {@code childListId} is the id of the
     * sub-agent card's nested {@code <ul>}-bearing list
     * ({@link TaskCardComponent#childListId(String)}); the renderer's
     * append-into-list path drops the card's {@code <li>} straight into
     * that list's {@code <ul>}, so the card nests under its sub-agent.
     */
    public UiPatch.Operation appendTaskCardInto(String childListId, TaskCardComponent card) {
        return UiPatch.Operation.append(childListId, card.render());
    }

    /**
     * REPLACE a task card in place. Targets the card's own id (the
     * {@code <li>}'s id), not the wrapper — that's the shape REPLACE
     * needs to morph an existing list item.
     */
    public UiPatch.Operation replaceTaskCard(TaskCardComponent card) {
        return UiPatch.Operation.replace(card.id(), card.render());
    }

    // ── Internal: welcome / empty ──────────────────────────────────────────


    private void addWelcomeOrEmpty(UiList list) {
        String welcome = agent.welcomeMessage();
        if (welcome != null && !welcome.isBlank()) {
            list.item(UiList.Item.of("welcome", agent.name())
                    .content(UiMarkdown.of("welcome-md", welcome)));
        } else {
            list.item(UiList.Item.of("empty", "No messages yet")
                    .description("Type a message below to start the conversation."));
        }
    }


    // ── Internal: historic task cards ──────────────────────────────────────

    /**
     * Builds every tool / sub-agent card across this component's full
     * history, in order. Used to render a <em>sub-session's</em> activity
     * as the nested body of its parent's sub-agent card — there's no
     * user-turn interleaving to respect inside a sub-session, so the whole
     * range is taken at once. Sub-agent calls recurse through this
     * component's {@link SubAgentTreeProvider}.
     */
    public List<TaskCardComponent> allHistoricTaskCards() {
        List<Message> sorted = history.stream()
                .sorted(Comparator.comparingInt(Message::sequenceNum))
                .toList();
        return toolCallHistory().buildHistoricTaskCards(sorted, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    /** The final assistant CHAT text in this component's history, or {@code null}. */
    public String lastAssistantText() {
        return history.stream()
                .filter(m -> m.type() == MessageType.CHAT)
                .filter(m -> m.senderType() != ParticipantType.USER)
                .max(Comparator.comparingInt(Message::sequenceNum))
                .map(Message::content)
                .orElse(null);
    }

    // ── Internal: the pieces this list is assembled from ───────────────────

    /** One message, rendered by its own component. */
    private UiList.Item messageItem(Message m, boolean isUser) {
        return new MessageComponent(sessionId, agent, m, isUser, DT_FMT).item();
    }

    /** Rebuilds the tool cards of a past turn. */
    private ToolCallHistory toolCallHistory() {
        return new ToolCallHistory(subAgentTree);
    }
}
