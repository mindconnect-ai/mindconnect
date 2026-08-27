package ai.mindconnect.adminui.ui.page;

import ai.mindconnect.adminui.ui.AdminPage;
import ai.mindconnect.adminui.ui.component.MemoryDetailSectionComponent;
import ai.mindconnect.adminui.ui.component.MemoryMasterListComponent;
import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.memory.domain.WorkingMemory;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiList;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiPatch;
import ai.mindconnect.ui.model.UiSection;

/**
 * Working-memory inspector page. Master-detail layout:
 * <ul>
 *   <li>Master ({@link MemoryMasterListComponent}) — clickable list of
 *       the system prompt + every windowed message.</li>
 *   <li>Detail ({@link MemoryDetailSectionComponent}) — Raw / LLM View
 *       tabs for the selected entry. The first render selects the
 *       system prompt; subsequent row clicks issue a patch that
 *       replaces just the detail pane.</li>
 * </ul>
 *
 * <p>The "Compress" header action POSTs to the compression endpoint;
 * the controller re-renders the whole page with the post-compression
 * snapshot, so the operator immediately sees the smaller window.
 */
public final class MemoryPage extends AdminPage {

    private final AgentSession session;
    private final AgentDefinition agent;
    private final WorkingMemory memory;

    private final MemoryMasterListComponent master;

    public MemoryPage(AgentSession session, AgentDefinition agent, WorkingMemory memory) {
        this.session = session;
        this.agent = agent;
        this.memory = memory;
        this.master = new MemoryMasterListComponent(session.id(), memory);
    }

    @Override
    public UiPage render() {
        String sessionId = session.id().toString();

        UiList masterUi = master.render();
        var detail = new MemoryDetailSectionComponent(
                memory, MemoryMasterListComponent.SYS_PROMPT_SEQ).render();

        // CSS class on the wrapper targets a two-column grid layout in
        // index.html so master + detail sit side by side.
        var inner = UiSection.of("memory-page-" + sessionId, null)
                .section("master", null, masterUi)
                .section("detail", null, detail)
                .withCssClass("memory-page");

        // Header actions on the master list (top of the visible content).

        UiAction compressAction = UiAction.primary("compress", "Compress").icon("collapse")
                .confirm("Compress unsummarized messages now? This summarises older "
                        + "tool results and chat turns to fit a smaller context window. "
                        + "The compressed messages stay in the conversation history.")
                .dispatch("POST", "/admin/api/sessions/" + sessionId + "/memory/compress");
        masterUi.action(compressAction);

        var section = UiSection.of("memory-session-" + sessionId,
                        "Working Memory — " + agent.name() + tokenUsageSuffix(memory))
                .section("memory", "Memory", inner);

        return UiPage.of("/admin/sessions/" + sessionId + "/memory", section);
    }

    /**
     * Patch for clicking a master-list row — replaces just the detail
     * pane with the selected entry's content. {@code seq} == {@link
     * MemoryMasterListComponent#SYS_PROMPT_SEQ} renders the system prompt.
     */
    public UiPatch selectEntry(int seq) {
        var detail = new MemoryDetailSectionComponent(memory, seq);
        return patch(UiPatch.Operation.replace(MemoryDetailSectionComponent.DETAIL_ID, detail.render()));
    }

    /** " — 1,234 / 200,000 tok (0.6%)" or empty when no memory snapshot. */
    private static String tokenUsageSuffix(WorkingMemory memory) {
        if (memory == null) return "";
        int used = memory.totalTokens();
        Integer max = memory.contextWindowTokens();
        if (max == null || max <= 0) {
            return used > 0 ? String.format("  —  %,d tok", used) : "";
        }
        double pct = 100.0 * used / max;
        return String.format("  —  %,d / %,d tok (%.1f%%)", used, max, pct);
    }
}
