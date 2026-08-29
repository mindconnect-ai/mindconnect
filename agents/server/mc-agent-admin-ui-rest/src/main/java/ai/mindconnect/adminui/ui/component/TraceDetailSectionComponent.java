package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.chatui.ui.UiComponent;
import ai.mindconnect.agent.domain.LlmCallTrace;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageType;
import ai.mindconnect.ui.ext.markdown.UiMarkdown;
import ai.mindconnect.ui.model.UiSection;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static ai.mindconnect.chatui.ui.SessionUiCommons.MAPPER;

/**
 * Detail-pane of the trace inspector — a stack of {@link
 * RoundtripCardComponent}s for the selected turn, with consecutive
 * sub-agent roundtrips wrapped in a collapsible {@code <details>}
 * group so the parent stream stays readable.
 *
 * <p>Stable DOM id ({@code trace-detail}) so detail-pane replacements
 * always target the same wrapper.
 */
public final class TraceDetailSectionComponent implements UiComponent {

    public static final String DETAIL_ID = "trace-detail";

    private final List<LlmCallTrace> turnTraces;
    private final List<Message> history;

    public TraceDetailSectionComponent(List<LlmCallTrace> turnTraces, List<Message> history) {
        this.turnTraces = turnTraces;
        this.history = history;
    }

    @Override
    public String id() {
        return DETAIL_ID;
    }

    @Override
    public UiSection render() {
        var detail = UiSection.of(DETAIL_ID, null);
        String headerText = turnTraces.isEmpty()
                ? "_Select a turn._"
                : "**Roundtrips (" + turnTraces.size() + ")**";
        detail.section("trace-header", null,
                UiMarkdown.of(DETAIL_ID + "-header-md", headerText));
        if (turnTraces.isEmpty()) {
            return detail;
        }

        Map<String, Message> resultsByCallId = indexToolResults(history);

        // Walk chronologically; group consecutive sub-agent roundtrips
        // that share the same parentTurnId into one collapsible block.
        // Top-level (depth==0) roundtrips flush any pending block first.
        SubAgentGroup pending = null;
        for (int i = 0; i < turnTraces.size(); i++) {
            LlmCallTrace t = turnTraces.get(i);
            int depth = t.context() != null ? t.context().depth() : 0;

            if (depth == 0) {
                if (pending != null) {
                    detail.section(pending.containerId, null, pending.toCollapsibleSection(resultsByCallId));
                    pending = null;
                }
                detail.section("rt-" + (i + 1), null,
                        new RoundtripCardComponent(i + 1, t, resultsByCallId).render());
                continue;
            }

            UUID parent = t.context() != null ? t.context().parentTurnId() : null;
            if (pending != null && !Objects.equals(pending.parentTurnId, parent)) {
                detail.section(pending.containerId, null, pending.toCollapsibleSection(resultsByCallId));
                pending = null;
            }
            if (pending == null) {
                String agentLabel = t.context() != null && t.context().agentName() != null
                        ? t.context().agentName() : "agent";
                pending = new SubAgentGroup(parent, agentLabel, "subagent-" + i);
            }
            pending.add(i + 1, t);
        }
        if (pending != null) {
            detail.section(pending.containerId, null, pending.toCollapsibleSection(resultsByCallId));
        }
        return detail;
    }

    /**
     * Indexes persisted TOOL_RESULT messages by their {@code toolCallId}
     * so the response-tab renderer can show each tool call together
     * with its own result block.
     */
    private static Map<String, Message> indexToolResults(List<Message> history) {
        Map<String, Message> out = new HashMap<>();
        if (history == null) return out;
        for (Message m : history) {
            if (m.type() != MessageType.TOOL_RESULT) continue;
            try {
                JsonNode node = MAPPER.readTree(m.content());
                String id = node.path("toolCallId").asText("");
                if (!id.isBlank()) out.put(id, m);
            } catch (Exception ignored) { /* skip malformed entries */ }
        }
        return out;
    }

    /** Accumulator for one collapsible run of consecutive sub-agent roundtrips. */
    private static final class SubAgentGroup {
        final UUID parentTurnId;
        final String agentLabel;
        final String containerId;
        final List<LlmCallTrace> traces = new ArrayList<>();
        final List<Integer> indices = new ArrayList<>();

        SubAgentGroup(UUID parentTurnId, String agentLabel, String containerId) {
            this.parentTurnId = parentTurnId;
            this.agentLabel = agentLabel;
            this.containerId = containerId;
        }

        void add(int globalIdx, LlmCallTrace t) {
            traces.add(t);
            indices.add(globalIdx);
        }

        UiSection toCollapsibleSection(Map<String, Message> resultsByCallId) {
            int totalPromptTokens = traces.stream().mapToInt(LlmCallTrace::promptTokens).sum();
            int totalCompletionTokens = traces.stream().mapToInt(LlmCallTrace::completionTokens).sum();
            long totalDurationMs = traces.stream().mapToLong(LlmCallTrace::durationMs).sum();
            boolean hasError = traces.stream().anyMatch(t -> t.errorStatus() != null);
            int depth = traces.get(0).context() != null ? traces.get(0).context().depth() : 1;

            String summary = "↳ " + agentLabel
                    + " (" + traces.size() + " call" + (traces.size() == 1 ? "" : "s")
                    + " · " + totalDurationMs + "ms"
                    + " · " + totalPromptTokens + "+" + totalCompletionTokens + " tok"
                    + (hasError ? " · ✗" : "")
                    + ")";

            var stack = UiSection.of(containerId + "-body", null);
            for (int n = 0; n < traces.size(); n++) {
                var card = new RoundtripCardComponent(indices.get(n), traces.get(n), resultsByCallId).render();
                stack.section(containerId + "-rt-" + n, null, card);
            }
            stack.collapsible(summary, false);
            stack.<UiSection>withCssClass("trace-depth-" + Math.min(depth, 5));
            return stack;
        }
    }
}
