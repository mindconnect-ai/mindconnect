package ai.mindconnect.adminui.ui.page;

import ai.mindconnect.adminui.ui.AdminPage;
import ai.mindconnect.adminui.ui.component.TraceDetailSectionComponent;
import ai.mindconnect.adminui.ui.component.TraceMasterListComponent;
import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.domain.LlmCallTrace;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiList;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiPatch;
import ai.mindconnect.ui.model.UiSection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * LLM-call-trace inspector page. Master-detail layout, same as the
 * working-memory inspector. The master list groups roundtrips by
 * top-level chat turn; the detail pane renders all roundtrips of the
 * selected turn (with sub-agent groups folded into collapsibles).
 *
 * <p>The turn-grouping (root-resolution via {@code parentTurnId}) is
 * a page-level concern because it spans the whole trace set, not any
 * single component — that's why it lives here as a method rather than
 * inside the master list.
 */
public final class TracesPage extends AdminPage {

    private final AgentSession session;
    private final AgentDefinition agent;
    private final List<LlmCallTrace> traces;
    private final List<Message> history;
    private final UUID selectedTurnId;
    private final Map<UUID, List<LlmCallTrace>> byTurn;

    public TracesPage(AgentSession session, AgentDefinition agent,
                       List<LlmCallTrace> traces, List<Message> history,
                       UUID selectedTurnId) {
        this.session = session;
        this.agent = agent;
        this.traces = traces;
        this.history = history;
        this.byTurn = groupByTurn(traces);
        this.selectedTurnId = selectedTurnId != null ? selectedTurnId : pickLatestTurnId(byTurn);
    }

    @Override
    public UiPage render() {
        String sessionId = session.id().toString();

        var master = new TraceMasterListComponent(session.id(), byTurn, history, selectedTurnId);
        var detail = new TraceDetailSectionComponent(
                byTurn.getOrDefault(selectedTurnId, List.of()), history);

        UiList masterUi = master.render();

        // CSS class on the wrapper targets a two-column grid layout in
        // index.html so master + detail sit side by side. We reuse the
        // memory-page grid CSS because the visual is identical.
        var inner = UiSection.of("traces-page-" + sessionId, null)
                .section("master", null, masterUi)
                .section("detail", null, detail.render())
                .withCssClass("memory-page");


        var section = UiSection.of("traces-session-" + sessionId,
                        "Traces — " + agent.name() + " (" + traces.size() + " calls)")
                .section("traces", "Traces", inner);

        return UiPage.of("/admin/sessions/" + sessionId + "/traces", section);
    }

    /**
     * Patch for clicking a turn in the master list: replaces the
     * detail pane with the roundtrips of that turn.
     */
    public UiPatch selectTurn(UUID turnId) {
        var detail = new TraceDetailSectionComponent(
                byTurn.getOrDefault(turnId, List.of()), history);
        return patch(UiPatch.Operation.replace(TraceDetailSectionComponent.DETAIL_ID, detail.render()));
    }

    // ── Turn grouping (root resolution via parentTurnId) ───────────────────

    /**
     * Groups every trace under the root (top-level) turn whose
     * call-tree it belongs to. A sub-agent roundtrip's parentTurnId
     * chain is walked upwards through the trace set until we hit a
     * trace with no parent — that trace's turnId is the root.
     *
     * <p>Iteration order of the returned map is chronological by the
     * root turn's earliest timestamp.
     */
    private static Map<UUID, List<LlmCallTrace>> groupByTurn(List<LlmCallTrace> traces) {
        Map<UUID, LlmCallTrace> firstByTurnId = new HashMap<>();
        for (LlmCallTrace t : traces) {
            if (t.context() == null || t.context().turnId() == null) continue;
            firstByTurnId.putIfAbsent(t.context().turnId(), t);
        }

        List<LlmCallTrace> sorted = traces.stream()
                .filter(t -> t.context() != null && t.context().turnId() != null)
                .sorted(Comparator.comparing(LlmCallTrace::startedAt))
                .toList();

        Map<UUID, List<LlmCallTrace>> byRoot = new LinkedHashMap<>();
        for (LlmCallTrace t : sorted) {
            UUID root = resolveRootTurnId(t, firstByTurnId);
            byRoot.computeIfAbsent(root, k -> new ArrayList<>()).add(t);
        }
        return byRoot;
    }

    /**
     * Walks {@code parentTurnId} edges upwards until it finds a trace
     * whose parentTurnId is null (= top-level), or until the parent
     * isn't in our trace set (orphan — treat the orphan's own turnId
     * as its own root). Guards against accidental cycles.
     */
    private static UUID resolveRootTurnId(LlmCallTrace t, Map<UUID, LlmCallTrace> firstByTurnId) {
        Set<UUID> guard = new HashSet<>();
        LlmCallTrace cur = t;
        while (cur != null && cur.context() != null) {
            UUID parent = cur.context().parentTurnId();
            if (parent == null) return cur.context().turnId();
            if (!guard.add(cur.context().turnId())) return cur.context().turnId();
            LlmCallTrace next = firstByTurnId.get(parent);
            if (next == null) return cur.context().turnId();
            cur = next;
        }
        return t.context().turnId();
    }

    /** Most recent turn = the last one in chronological iteration order. */
    private static UUID pickLatestTurnId(Map<UUID, List<LlmCallTrace>> byTurn) {
        UUID last = null;
        for (UUID id : byTurn.keySet()) last = id;
        return last;
    }
}
