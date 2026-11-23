package ai.mindconnect.adminui.ui.page;

import ai.mindconnect.adminui.ui.AdminPage;
import ai.mindconnect.adminui.ui.component.TraceTableComponent;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.trace.domain.LlmCallTraceHeader;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiSection;

import java.util.List;

/**
 * LLM-call-trace inspector page: the session's calls (including those of
 * its sub-agents) as one searchable, sortable, paged table, each row
 * opening a detail dialog with the request, the response and the raw
 * event stream of that call.
 */
public final class TracesPage extends AdminPage {

    private final SessionId session;
    private final String agentName;
    private final List<? extends LlmCallTraceHeader> traces;

    /** @param agentName the agent the session runs as, for the heading */
    public TracesPage(SessionId session, String agentName, List<? extends LlmCallTraceHeader> traces) {
        this.session = session;
        this.agentName = agentName;
        this.traces = traces;
    }

    @Override
    public UiPage render() {
        String sessionId = session.value();
        var table = new TraceTableComponent(session, traces).render();
        var section = UiSection.of("traces-session-" + sessionId,
                        "Traces — " + agentName + " (" + traces.size() + " calls)")
                .section("traces", null, table);
        return UiPage.of("/admin/sessions/" + sessionId + "/traces", section);
    }
}
