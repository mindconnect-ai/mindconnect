package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.adminui.ui.UiComponent;
import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.port.out.AgentSessionRepository;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiTable;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tabular view of an agent's chat sessions with Open / Delete row
 * actions and a header "New Session" action. The session list is read
 * from {@link AgentSessionRepository} at render time so it stays
 * current after deletes.
 */
public final class SessionTableComponent implements UiComponent {

    private static final DateTimeFormatter DT_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final AgentDefinition agent;
    private final String userId;
    private final AgentSessionRepository sessionRepository;
    private final UUID selectedSessionId;

    public SessionTableComponent(AgentDefinition agent, String userId,
                                  AgentSessionRepository sessionRepository) {
        this(agent, userId, sessionRepository, null);
    }

    public SessionTableComponent(AgentDefinition agent, String userId,
                                  AgentSessionRepository sessionRepository,
                                  UUID selectedSessionId) {
        this.agent = agent;
        this.userId = userId;
        this.sessionRepository = sessionRepository;
        this.selectedSessionId = selectedSessionId;
    }

    @Override
    public String id() {
        return "session-table-" + agent.id();
    }

    @Override
    public UiTable render() {
        List<AgentSession> sessions = sessionRepository
                .findByAgentDefinitionId(agent.id(), agent.namespace(), userId);

        var table = UiTable.of(id(), "Sessions")
                .action(UiAction.primary("new-session", "New Session").icon("add")
                        .dispatch("POST", "/admin/api/agents/" + agent.id() + "/sessions"))
                .column(UiTable.Column.text("title", "Title").asSortable())
                .column(UiTable.Column.text("status", "Status"))
                .column(UiTable.Column.text("userId", "User"))
                .column(UiTable.Column.date("startedAt", "Started").asSortable())
                .column(UiTable.Column.date("completedAt", "Completed"))
                .rowAction(UiAction.secondary("open", "Open").icon("show")
                        .dispatch("GET", "/admin/api/sessions/{id}"))
                .rowAction(UiAction.danger("delete", "Delete").icon("delete")
                        .confirm("Delete this session?")
                        .dispatch("DELETE", "/admin/api/agents/" + agent.id() + "/sessions/{id}"));

        for (AgentSession s : sessions) {
            table.row(Map.of(
                "id",          s.id().toString(),
                "title",       s.title() != null ? s.title() : "(untitled)",
                "status",      s.status() != null ? s.status().name() : "",
                "userId",      s.userId() != null ? s.userId() : "",
                "startedAt",   s.startedAt() != null ? DT_FMT.format(s.startedAt()) : "",
                "completedAt", s.completedAt() != null ? DT_FMT.format(s.completedAt()) : ""
            ));
        }
        if (selectedSessionId != null) table.selectedRow(selectedSessionId.toString());
        return table;
    }
}
