package ai.mindconnect.adminui.ui.page;

import ai.mindconnect.adminui.ui.AdminPage;
import ai.mindconnect.adminui.ui.component.TraceTableComponent;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.domain.view.LlmCallTraceHeader;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiSection;

import java.util.List;

/**
 * LLM-call-trace inspector page: the session's calls (including those of
 * its sub-agents) as one table, each row opening a detail dialog with the
 * request, the response and the raw event stream of that call.
 */
public final class TracesPage extends AdminPage {

    private final AgentSession session;
    private final AgentDefinition agent;
    private final List<? extends LlmCallTraceHeader> traces;

    public TracesPage(AgentSession session, AgentDefinition agent,
                      List<? extends LlmCallTraceHeader> traces) {
        this.session = session;
        this.agent = agent;
        this.traces = traces;
    }

    @Override
    public UiPage render() {
        String sessionId = session.id().value();
        var table = new TraceTableComponent(session.id(), traces).render();
        var section = UiSection.of("traces-session-" + sessionId,
                        "Traces — " + agent.name() + " (" + traces.size() + " calls)")
                .section("traces", null, table);
        return UiPage.of("/admin/sessions/" + sessionId + "/traces", section);
    }
}
