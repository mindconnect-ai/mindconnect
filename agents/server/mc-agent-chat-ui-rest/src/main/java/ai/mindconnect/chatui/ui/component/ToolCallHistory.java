package ai.mindconnect.chatui.ui.component;

import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageType;
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
 * caller supplies.
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
    public List<TaskCardComponent> buildHistoricTaskCards(List<Message> sorted,
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
    }}
