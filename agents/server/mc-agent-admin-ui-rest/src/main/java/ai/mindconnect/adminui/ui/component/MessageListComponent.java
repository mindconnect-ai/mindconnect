package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.adminui.ui.UiComponent;
import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.memory.domain.WorkingMemory;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageType;
import ai.mindconnect.message.domain.ParticipantType;
import ai.mindconnect.ui.ext.markdown.UiMarkdown;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiList;
import ai.mindconnect.ui.model.UiPatch;
import ai.mindconnect.ui.model.UiTrigger;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import static ai.mindconnect.adminui.assembler.session.SessionUiCommons.DT_FMT;
import static ai.mindconnect.adminui.assembler.session.SessionUiCommons.MAPPER;

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
        var list = UiList.of(id(), "Conversation");
        list.withCssClass("chat-container");
        list.headerExtra(tokenUsageBar(memory));

        // Header action links — navigation only (no method), so the bus
        // calls navigate(href) instead of fetchPage(method, href).
        addHeaderActions(list);

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
                List<TaskCardComponent> tasks = buildHistoricTaskCards(sorted, prevAgentSeq, m.sequenceNum());
                for (TaskCardComponent t : tasks) {
                    // Reuse the wrapper render to get the same <li> shape.
                    list.item(((UiList) t.render()).getItems().get(0));
                }
            }
            list.item(chatItem(m, isUser));
            prevAgentSeq = m.sequenceNum();
        }
        // The IN-FLIGHT turn's activity: task messages after the last CHAT
        // have no agent answer to group under yet — render them anyway, or a
        // mid-turn rebuild (approval answered, page reloaded) would make the
        // running tool and sub-agent cards vanish until the turn ends.
        for (TaskCardComponent t : buildHistoricTaskCards(sorted, prevAgentSeq, Integer.MAX_VALUE)) {
            list.item(((UiList) t.render()).getItems().get(0));
        }
        // Open approval questions live ONLY in the ToolApprovalStore now
        // (the gate parks the tool task and registers the card there) — the
        // controller hands them in, rendered after the history.
        bubbledApprovalCards.forEach(list::item);
        return list;
    }

    // ── Approval cards ─────────────────────────────────────────────────────

    /** The tool name and pretty-printed arguments out of a request's content JSON. */
    public record ApprovalCall(String toolName, String argsJson) { }

    public static ApprovalCall parseApprovalContent(String content) {
        String toolName = "?";
        String argsJson = "{}";
        try {
            var node = APPROVAL_MAPPER.readTree(content);
            if (node.hasNonNull("name")) toolName = node.get("name").asText();
            if (node.has("arguments")) {
                argsJson = APPROVAL_MAPPER.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(node.get("arguments"));
            }
        } catch (Exception ignored) {
            // unreadable content — the card still renders with the raw call id
        }
        return new ApprovalCall(toolName, argsJson);
    }

    /**
     * The approval card — ONE shape for every open question (root tool or
     * sub-agent alike): the answer is a plain dispatch identified by callId
     * alone, the ToolApprovalStore knows the rest. The turn never ended, so
     * there is no stream to start — the ORIGINAL stream carries the
     * continuation once the parked tool task is woken.
     */
    public static UiList.Item approvalCard(UUID sessionId, String callId,
                                           String toolName, String argsJson, String time) {
        String base = "/admin/api/sessions/" + sessionId + "/approval"
                + "?callId=" + java.net.URLEncoder.encode(callId, java.nio.charset.StandardCharsets.UTF_8);

        // Buttons live IN the card body (a plain stack renders them as real,
        // always-visible buttons — item.action() would make them hover icons);
        // the arguments fold away behind their own collapsible row.
        var intro = UiMarkdown.of("approval-intro-" + callId,
                "The agent wants to run **`" + toolName + "`**.");
        var params = ai.mindconnect.ui.model.UiList.of("approval-params-list-" + callId, null);
        params.item(UiList.Item.of("approval-params-" + callId, "Parameters")
                .collapsible("show", false)
                .content(UiMarkdown.of("approval-args-" + callId,
                        "```json\n" + argsJson + "\n```")));
        var buttons = ai.mindconnect.ui.model.UiStack.of(
                        UiAction.danger("approval-deny-" + callId, "Deny")
                                .dispatch("POST", base + "&approved=false&scope=once"),
                        UiAction.secondary("approval-once-" + callId, "Allow once")
                                .dispatch("POST", base + "&approved=true&scope=once"),
                        UiAction.primary("approval-session-" + callId, "Allow for this session")
                                .dispatch("POST", base + "&approved=true&scope=session"))
                .direction(ai.mindconnect.ui.model.UiStack.Direction.HORIZONTAL)
                .gap(8);
        var bodyStack = ai.mindconnect.ui.model.UiStack.of(intro, params, buttons);
        bodyStack.setId("approval-body-" + callId);
        bodyStack.withCssClass("approval-request");
        return UiList.Item.of("approval-" + callId, "Approval required  [" + time + "]")
                .content(bodyStack);
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

    private static final com.fasterxml.jackson.databind.ObjectMapper APPROVAL_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

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

    // ── Internal: header actions + welcome/empty ───────────────────────────

    private void addHeaderActions(UiList list) {
        String agentId = agent.id().toString();

        // Sub-agent sessions link back up to the session that spawned them.
        // Sprite icons instead of emoji: same icon language as the rest of
        // the chrome, consistent size and color.
        if (parentSessionId != null) {
            UiAction parentAction = UiAction.link("parent", "Parent Session").icon("arrow-up");
            parentAction.setHref("/admin/sessions/" + parentSessionId);
            list.action(parentAction);
        }

        UiAction backAction = UiAction.link("back", "Back to Agent").icon("back");
        backAction.setHref("/admin/agents/" + agentId
                + "?section=sessions&row=" + sessionId);
        list.action(backAction);

        // The session tools open as dialogs over the conversation — a chat
        // shouldn't navigate away from itself for a quick look at memory,
        // traces, todos or the workspace.
        list.action(UiAction.link("memory", "Working Memory").icon("chart")
                .dispatch("GET", "/admin/api/sessions/" + sessionId + "/memory?dialog=true"));
        list.action(UiAction.link("traces", "Traces").icon("list")
                .dispatch("GET", "/admin/api/sessions/" + sessionId + "/traces?dialog=true"));
        list.action(UiAction.link("todos", "Todos").icon("check")
                .dispatch("GET", "/admin/api/sessions/" + sessionId + "/todos?dialog=true"));
        list.action(UiAction.link("workspace", "Workspace").icon("folder")
                .dispatch("GET", "/admin/api/sessions/" + sessionId + "/workspace?dialog=true"));
    }

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

    // ── Internal: chat-message item ────────────────────────────────────────

    private UiList.Item chatItem(Message m, boolean isUser) {
        String speaker = isUser ? "You" : agent.name();
        String time    = DT_FMT.format(m.sentAt());
        String label   = speaker + "  [" + time + "]" + messageTokenSuffix(m);
        String css     = isUser ? "user-message" : "bot-message";
        int seq        = m.sequenceNum();

        var item = UiList.Item.of(m.id().toString(), label)
                .content(UiMarkdown.of("msg-" + m.id(), m.content()).withCssClass(css));

        // Regenerate (USER messages only): delete this message + everything
        // after it, then re-run the turn (streaming) with the same text. Uses
        // the STREAM behaviour so the live tokens/task-cards flow exactly like
        // a normal send.
        if (isUser) {
            item.action(UiAction.icon("regen-" + m.id(), "🔄")
                    .confirm("Delete the response(s) after this message and generate a new one?")
                    .onClick(UiTrigger.stream("POST",
                            "/admin/api/sessions/" + sessionId
                                    + "/messages/" + seq + "/regenerate", null)));
        }

        // Delete-from-here: remove this message and every message after it.
        // toSeq = MAX_VALUE → the range delete runs to the end of the
        // conversation. Sub-agent sessions are not cleaned up.
        item.action(UiAction.icon("delete-" + m.id(), "🗑")
                .style(UiAction.Style.DANGER)
                .confirm("Delete this message and all following messages?")
                .dispatch("DELETE",
                        "/admin/api/sessions/" + sessionId
                                + "/messages?fromSeq=" + seq + "&toSeq=" + Integer.MAX_VALUE));
        return item;
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
        return buildHistoricTaskCards(sorted, Integer.MIN_VALUE, Integer.MAX_VALUE);
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

    /**
     * Builds one task card per tool / sub-agent call from persisted
     * TOOL_CALL + TOOL_RESULT messages that lived between the previous
     * agent CHAT ({@code fromSeq}, exclusive) and the current one
     * ({@code toSeq}, exclusive).
     *
     * <p>Pairs TOOL_CALL entries (by tool-call id from the JSON payload)
     * with the matching TOOL_RESULT so the card shows both input and
     * output. Orphan TOOL_RESULTs (e.g. from older runs) render as
     * output-only cards.
     */
    List<TaskCardComponent> buildHistoricTaskCards(List<Message> sorted,
                                                           int fromSeq, int toSeq) {
        LinkedHashMap<String, List<TaskCardComponent>> byCallId = new LinkedHashMap<>();
        java.util.Map<String, String> argsByCallId = new java.util.HashMap<>();
        java.util.Map<String, String> nameByCallId = new java.util.HashMap<>();

        // Pass 1: collect inputs from TOOL_CALL messages.
        for (Message m : sorted) {
            if (m.sequenceNum() <= fromSeq || m.sequenceNum() >= toSeq) continue;
            if (m.type() != MessageType.TOOL_CALL) continue;
            try {
                JsonNode node  = MAPPER.readTree(m.content());
                JsonNode calls = node.path("toolCalls");
                if (calls.isArray()) {
                    for (JsonNode tc : calls) {
                        String id   = tc.path("id").asText("");
                        String name = tc.path("name").asText("tool");
                        JsonNode args = tc.path("arguments");
                        String prettyArgs = MAPPER.writerWithDefaultPrettyPrinter()
                                .writeValueAsString(args);
                        nameByCallId.put(id, name);
                        argsByCallId.put(id, prettyArgs);
                    }
                }
            } catch (Exception ignored) {}
        }

        // Pass 2: collect outputs from TOOL_RESULT messages, build cards.
        for (Message m : sorted) {
            if (m.sequenceNum() <= fromSeq || m.sequenceNum() >= toSeq) continue;
            if (m.type() != MessageType.TOOL_RESULT) continue;
            try {
                JsonNode node = MAPPER.readTree(m.content());
                String callId   = node.path("toolCallId").asText("");
                String toolName = node.path("toolName").asText(nameByCallId.getOrDefault(callId, "tool"));
                String result   = node.path("result").asText("");
                long   duration = m.durationMs() != null ? m.durationMs() : 0L;

                boolean isSubAgent = "run_agent".equals(toolName) || "run_agents".equals(toolName);
                boolean failed     = result != null && result.startsWith("Error:");
                String key = callId.isEmpty() ? ("task-hist-" + m.id()) : callId;

                // Sub-agent call WITH a persisted result → done. Prefer the
                // recursively-built nested tree (child sessions found by
                // parentToolCallId); fall back to a flat card only when no
                // sub-sessions resolve.
                if (isSubAgent && !callId.isEmpty()) {
                    List<TaskCardComponent> subCards = subAgentTree.cardsFor(
                            callId, false, argsByCallId.get(callId), result);
                    if (subCards != null && !subCards.isEmpty()) {
                        byCallId.put(key, subCards);
                        continue;
                    }
                }

                String header;
                String displayName;
                if (isSubAgent) {
                    displayName = extractSubAgentName(argsByCallId.get(callId)).orElse("sub-agent");
                    header = failed
                            ? TaskCardComponent.failedSubAgentHeader(displayName)
                            : TaskCardComponent.doneSubAgentHeader(displayName, duration);
                } else {
                    displayName = toolName;
                    header = failed
                            ? TaskCardComponent.failedToolHeader(displayName, duration)
                            : TaskCardComponent.doneToolHeader(displayName, duration);
                }

                String body = TaskCardComponent.taskCardBody(argsByCallId.get(callId), result);
                String nodeId = "task-hist-" + m.id();
                byCallId.put(key, List.of(TaskCardComponent.historic(nodeId, header, body)));
            } catch (Exception ignored) {}
        }

        // Pass 3: sub-agent TOOL_CALLs that have NO TOOL_RESULT yet — i.e.
        // sub-agents still running when the page was (re)loaded mid-turn.
        // Their assistant TOOL_CALL message is persisted before dispatch, so
        // the callId is known here even though the result isn't. Surface the
        // (running) nested tree via the provider, which finds the ACTIVE
        // child sessions. Inserted in TOOL_CALL order so they read correctly.
        for (Message m : sorted) {
            if (m.sequenceNum() <= fromSeq || m.sequenceNum() >= toSeq) continue;
            if (m.type() != MessageType.TOOL_CALL) continue;
            try {
                JsonNode calls = MAPPER.readTree(m.content()).path("toolCalls");
                if (!calls.isArray()) continue;
                for (JsonNode tc : calls) {
                    String id   = tc.path("id").asText("");
                    String name = tc.path("name").asText("");
                    boolean isSubAgent = "run_agent".equals(name) || "run_agents".equals(name);
                    if (!isSubAgent || id.isEmpty() || byCallId.containsKey(id)) continue;
                    List<TaskCardComponent> subCards = subAgentTree.cardsFor(
                            id, true, argsByCallId.get(id), null);
                    if (subCards != null && !subCards.isEmpty()) {
                        byCallId.put(id, subCards);
                    }
                }
            } catch (Exception ignored) {}
        }

        return byCallId.values().stream().flatMap(List::stream).toList();
    }

    /** Pulls the "name" field out of a run_agent arguments JSON. */
    private java.util.Optional<String> extractSubAgentName(String argsJson) {
        if (argsJson == null) return java.util.Optional.empty();
        try {
            JsonNode node = MAPPER.readTree(argsJson);
            String name = node.path("name").asText("");
            return name.isBlank() ? java.util.Optional.empty() : java.util.Optional.of(name);
        } catch (Exception e) {
            return java.util.Optional.empty();
        }
    }

    // ── Internal: token-usage formatting ───────────────────────────────────

    /**
     * Context-window usage as a compact progress bar + readout for the
     * header's extra slot. Tints warning at 75%%, error at 90%%. Null (no
     * bar) when there is no memory snapshot.
     */
    private ai.mindconnect.ui.model.UiNode tokenUsageBar(WorkingMemory memory) {
        if (memory == null) return null;
        int used = memory.totalTokens();
        Integer max = memory.contextWindowTokens();
        var label = ai.mindconnect.ui.model.UiText
                .of(id() + ":tok-label", tokenUsageSuffix(memory).replace("  —  ", ""))
                .withCssClass("chat-token-text");
        if (max == null || max <= 0) {
            return used > 0 ? label : null;
        }
        double pct = 100.0 * used / max;
        var bar = ai.mindconnect.ui.model.UiProgress.of(used, max).showValue(false);
        bar.setId(id() + ":tok-bar");
        if (pct >= 90) bar.status(ai.mindconnect.ui.model.UiProgress.Status.ERROR);
        else if (pct >= 75) bar.status(ai.mindconnect.ui.model.UiProgress.Status.WARNING);
        var wrap = ai.mindconnect.ui.model.UiStack.of(id() + ":tok");
        wrap.direction(ai.mindconnect.ui.model.UiStack.Direction.HORIZONTAL);
        wrap.gap(8);
        wrap.withCssClass("chat-token-usage");
        wrap.child(bar).child(label);
        return wrap;
    }

    /** " — 1,234 / 200,000 tok (0.6%)" or empty when no memory snapshot. */
    private String tokenUsageSuffix(WorkingMemory memory) {
        if (memory == null) return "";
        int used = memory.totalTokens();
        Integer max = memory.contextWindowTokens();
        if (max == null || max <= 0) {
            return used > 0 ? String.format("  —  %,d tok", used) : "";
        }
        double pct = 100.0 * used / max;
        return String.format("  —  %,d / %,d tok (%.1f%%)", used, max, pct);
    }

    /** " · 42 tok" for a single message; empty when not counted. */
    private String messageTokenSuffix(Message m) {
        Integer t = m.tokenCount();
        if (t == null || t <= 0) return "";
        return "  ·  " + String.format("%,d", t) + " tok";
    }
}
