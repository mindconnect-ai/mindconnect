package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.adminui.ui.UiComponent;
import ai.mindconnect.agent.domain.LlmCallTrace;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageType;
import ai.mindconnect.message.domain.ParticipantType;
import ai.mindconnect.ui.model.UiList;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static ai.mindconnect.adminui.assembler.session.SessionUiCommons.DT_FMT;
import static ai.mindconnect.adminui.assembler.session.SessionUiCommons.previewOf;

/**
 * Master-list pane of the trace inspector — one row per top-level chat
 * turn. Each row labels the turn with its time, total roundtrips,
 * sub-agent count (if any), aggregated duration and token usage; the
 * description shows a one-line preview of the user's prompt and the
 * agent's reply (taken from the persisted conversation messages so the
 * operator can pick a turn at a glance).
 *
 * <p>Sub-agent roundtrips fold under their root turn — they do
 * <i>not</i> appear as separate master rows. The selected turn (most
 * recent by default) is marked with a {@code ◀} suffix.
 */
public final class TraceMasterListComponent implements UiComponent {

    private final UUID sessionId;
    private final Map<UUID, List<LlmCallTrace>> byTurn;
    private final List<Message> history;
    private final UUID selectedTurnId;

    public TraceMasterListComponent(UUID sessionId,
                                     Map<UUID, List<LlmCallTrace>> byTurn,
                                     List<Message> history,
                                     UUID selectedTurnId) {
        this.sessionId = sessionId;
        this.byTurn = byTurn;
        this.history = history;
        this.selectedTurnId = selectedTurnId;
    }

    @Override
    public String id() {
        return "traces-list-" + sessionId;
    }

    @Override
    public UiList render() {
        var list = UiList.of(id(), "Turns (" + byTurn.size() + ")");
        list.withCssClass("memory-master");

        if (byTurn.isEmpty()) {
            list.item(UiList.Item.of("traces-empty", "No traces yet")
                    .description("Send a message in the chat to capture traces."));
            return list;
        }

        // Index messages by turnId once for fast lookup.
        Map<UUID, List<Message>> messagesByTurn = new LinkedHashMap<>();
        for (Message m : history) {
            if (m.turnId() != null) {
                messagesByTurn.computeIfAbsent(m.turnId(), k -> new ArrayList<>()).add(m);
            }
        }

        for (var entry : byTurn.entrySet()) {
            UUID turnId = entry.getKey();
            List<LlmCallTrace> turnTraces = entry.getValue();
            LlmCallTrace first = turnTraces.get(0);

            String time = DT_FMT.format(first.startedAt());
            int totalPromptTokens = turnTraces.stream().mapToInt(LlmCallTrace::promptTokens).sum();
            int totalCompletionTokens = turnTraces.stream().mapToInt(LlmCallTrace::completionTokens).sum();
            long totalDurationMs = turnTraces.stream().mapToLong(LlmCallTrace::durationMs).sum();
            boolean hasError = turnTraces.stream().anyMatch(t -> t.errorStatus() != null);
            // Sub-agent count: useful to see which turns dispatched delegate work.
            long subAgentCalls = turnTraces.stream()
                    .filter(t -> t.context() != null && t.context().depth() > 0)
                    .count();

            String label = time + " · " + turnTraces.size() + " call"
                    + (turnTraces.size() == 1 ? "" : "s")
                    + (subAgentCalls > 0 ? " (" + subAgentCalls + " sub-agent)" : "")
                    + " · " + totalDurationMs + "ms · "
                    + totalPromptTokens + "+" + totalCompletionTokens + " tok"
                    + (hasError ? " ✗" : "")
                    + (selectedTurnId != null && selectedTurnId.equals(turnId) ? " ◀" : "");

            String userPreview = userPromptPreview(messagesByTurn.get(turnId));
            String agentPreview = agentResponsePreview(messagesByTurn.get(turnId));
            String desc = "You: " + userPreview + "\nAgent: " + agentPreview;

            list.item(UiList.Item.of("trace-turn-" + turnId, label)
                    .description(desc)
                    .dispatch("GET", "/admin/api/sessions/" + sessionId
                            + "/traces?turnId=" + turnId));
        }
        return list;
    }

    /** First USER CHAT message of the turn, truncated to one line. */
    private static String userPromptPreview(List<Message> turnMsgs) {
        if (turnMsgs == null) return "(no prompt)";
        return turnMsgs.stream()
                .filter(m -> m.senderType() == ParticipantType.USER && m.type() == MessageType.CHAT)
                .findFirst()
                .map(Message::content)
                .map(c -> previewOf(c))
                .orElse("(no prompt)");
    }

    /** Last AGENT CHAT message of the turn, truncated to one line. */
    private static String agentResponsePreview(List<Message> turnMsgs) {
        if (turnMsgs == null) return "(no response yet)";
        Message last = null;
        for (Message m : turnMsgs) {
            if (m.senderType() == ParticipantType.AGENT && m.type() == MessageType.CHAT) {
                last = m;
            }
        }
        return last != null ? previewOf(last.content()) : "(no response yet)";
    }
}
