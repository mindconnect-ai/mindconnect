package ai.mindconnect.adminui.ui.page;

import ai.mindconnect.adminui.ui.AdminPage;
import ai.mindconnect.adminui.ui.component.MemoryDetailSectionComponent;
import ai.mindconnect.adminui.ui.component.MemoryMasterListComponent;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.capabilities.memory.WorkingMemory;
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

    private final SessionId sessionId;
    private final String agentName;
    private final WorkingMemory memory;
    private final MemoryMasterListComponent master;

    /** @param agentName the agent the session runs as */
    public MemoryPage(SessionId sessionId, String agentName, WorkingMemory memory) {
        this.sessionId = sessionId;
        this.agentName = agentName;
        this.memory = memory;
        this.master = new MemoryMasterListComponent(sessionId, memory);
    }

    @Override
    public UiPage render() {
        String sessionId = this.sessionId.value();

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
                        "Working Memory — " + agentName + tokenUsageSuffix(memory))
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

    /** " — 1,234 / 200,000 tok (0.6%)"; without the second figure for a model whose window is not known. */
    private static String tokenUsageSuffix(WorkingMemory memory) {
        int used = memory.totalTokens();
        if (memory.contextWindowTokens().isEmpty() || memory.contextWindowTokens().getAsInt() <= 0) {
            return used > 0 ? String.format("  —  %,d tok", used) : "";
        }
        int max = memory.contextWindowTokens().getAsInt();
        return String.format("  —  %,d / %,d tok (%.1f%%)", used, max, 100.0 * used / max);
    }
}
