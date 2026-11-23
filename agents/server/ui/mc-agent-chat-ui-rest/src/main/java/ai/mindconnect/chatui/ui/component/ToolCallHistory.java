package ai.mindconnect.chatui.ui.component;

import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.management.api.ManagedMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * Turns persisted {@code TOOL_CALL} / {@code TOOL_RESULT} messages back into
 * the cards the chat showed while they were running.
 *
 * <p>Not a component but the assembler behind one: it pairs a call with its
 * result by tool-call id and hands out {@link TaskCardComponent}s, so
 * reloading a conversation looks like watching it happen. Sub-agent calls
 * recurse through the {@link MessageListComponent.SubAgentTreeProvider} the
 * caller supplies. The reasoning that led to a call (the TOOL_CALL message's
 * {@code metadata.thinking}) becomes a thinking card ahead of the call's own,
 * where it stood while the turn ran.
 *
 * <p>Cards come out in the order the calls were made, not the order their
 * results landed: every TOOL_CALL message reserves its slots first — the
 * thought, then one per call — and the result pass fills a slot in place,
 * so two parallel calls whose results persist the other way round still
 * read thought, first call, second call.
 */
public final class ToolCallHistory {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MessageListComponent.SubAgentTreeProvider subAgentTree;

    public ToolCallHistory(MessageListComponent.SubAgentTreeProvider subAgentTree) {
        this.subAgentTree = subAgentTree;
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
    public List<TaskCardComponent> buildHistoricTaskCards(List<ManagedMessage> sorted,
                                                           int fromSeq, int toSeq) {
        // Insertion order is render order; a slot reserved here keeps its
        // place when a later pass fills it (LinkedHashMap.put on a known key).
        LinkedHashMap<String, List<TaskCardComponent>> byCallId = new LinkedHashMap<>();
        java.util.Map<String, String> argsByCallId = new java.util.HashMap<>();
        java.util.Map<String, String> nameByCallId = new java.util.HashMap<>();

        // Pass 1: collect inputs from TOOL_CALL messages, and reserve the
        // slots: the thought that led to the calls, then one per call.
        for (ManagedMessage m : sorted) {
            if (m.sequence() <= fromSeq || m.sequence() >= toSeq) continue;
            if (m.message().type() != TurnMessage.Type.TOOL_CALL) continue;
            Thoughts.of(m).ifPresent(thought ->
                    byCallId.put("think-" + m.id(), List.of(Thoughts.card(m, thought))));
            try {
                for (TurnMessage.ToolCall call : m.message().toolCalls()) {
                    String id = call.callId() == null ? "" : call.callId();
                    nameByCallId.put(id, call.name() == null ? "tool" : call.name());
                    argsByCallId.put(id, MAPPER.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(call.arguments()));
                    if (!id.isEmpty()) byCallId.putIfAbsent(id, List.of());
                }
            } catch (Exception ignored) {}
        }

        // Pass 2: collect outputs from TOOL_RESULT messages, build cards.
        for (ManagedMessage m : sorted) {
            if (m.sequence() <= fromSeq || m.sequence() >= toSeq) continue;
            if (m.message().type() != TurnMessage.Type.TOOL_RESULT) continue;
            try {
                java.util.Map<String, Object> metadata = m.message().metadata();
                String callId   = metadata.get("callId") instanceof String id ? id : "";
                String toolName = metadata.get("toolName") instanceof String name && !name.isEmpty()
                        ? name : nameByCallId.getOrDefault(callId, "tool");
                String result   = m.message().text();
                long   duration = m.durationMillis() != null ? m.durationMillis() : 0L;

                boolean isSubAgent = "run_agent".equals(toolName) || "run_agents".equals(toolName);
                // The flag is what the runtime records (a cancel stub, a denied
                // approval); the prefix is how older results said so.
                boolean failed     = "true".equals(String.valueOf(metadata.get("failed")))
                        || result.startsWith("Error:");
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
        for (ManagedMessage m : sorted) {
            if (m.sequence() <= fromSeq || m.sequence() >= toSeq) continue;
            if (m.message().type() != TurnMessage.Type.TOOL_CALL) continue;
            try {
                for (TurnMessage.ToolCall call : m.message().toolCalls()) {
                    String id   = call.callId() == null ? "" : call.callId();
                    String name = call.name();
                    boolean isSubAgent = "run_agent".equals(name) || "run_agents".equals(name);
                    if (!isSubAgent || id.isEmpty() || !byCallId.getOrDefault(id, List.of()).isEmpty()) continue;
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

    /** The agent a run_agent call names, or the agents of a run_agents call's tasks, in their order. */
    private java.util.Optional<String> extractSubAgentName(String argsJson) {
        if (argsJson == null) return java.util.Optional.empty();
        try {
            JsonNode node = MAPPER.readTree(argsJson);
            List<String> names = new java.util.ArrayList<>();
            names.add(node.path("name").asText(""));
            node.path("tasks").forEach(task -> names.add(task.path("name").asText("")));
            names.removeIf(String::isBlank);
            return names.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(String.join(", ", names));
        } catch (Exception e) {
            return java.util.Optional.empty();
        }
    }
}
