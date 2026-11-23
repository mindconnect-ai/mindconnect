package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.chatui.ui.UiComponent;
import ai.mindconnect.agent.AgentId;
import ai.mindconnect.adminui.ui.controller.AgentUiController;
import ai.mindconnect.chatui.ui.controller.ChatUiController;

import static ai.mindconnect.ui.mvc.UiActions.ROW_ID;
import static ai.mindconnect.ui.mvc.UiActions.trigger;
import static org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder.on;
import ai.mindconnect.agent.management.api.ManagedSession;
import ai.mindconnect.agent.management.api.SessionManagement;
import ai.mindconnect.agent.runtime.api.domain.UserId;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiTable;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import ai.mindconnect.agent.SessionId;

/**
 * Tabular view of an agent's chat sessions with Open / Delete row
 * actions and a header "New Session" action. The session list is read
 * from the {@link SessionManagement} at render time so it stays
 * current after deletes.
 */
public final class SessionTableComponent implements UiComponent {

    private static final DateTimeFormatter DT_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final AgentId agent;
    private final String userId;
    private final SessionManagement sessions;
    private final SessionId selectedSessionId;

    public SessionTableComponent(AgentId agent, String userId, SessionManagement sessions) {
        this(agent, userId, sessions, null);
    }

    public SessionTableComponent(AgentId agent, String userId, SessionManagement sessions,
                                  SessionId selectedSessionId) {
        this.agent = agent;
        this.userId = userId;
        this.sessions = sessions;
        this.selectedSessionId = selectedSessionId;
    }

    @Override
    public String id() {
        return "session-table-" + agent.value();
    }

    @Override
    public UiTable render() {
        // The user's sessions that were opened with this stored agent.
        List<ManagedSession> ofAgent = sessions.list(UserId.of(userId)).stream()
                .filter(session -> session.agentId().map(id -> id.value().equals(agent.value())).orElse(false))
                .toList();

        var table = UiTable.of(id(), "Sessions").stackOnMobile(true)
                .action(UiAction.primary("new-session", "New Session").icon("add")
                        .onClick(trigger(on(ChatUiController.class).startSession(agent.value(), null))))
                .column(UiTable.Column.text("title", "Title").asSortable())
                .column(UiTable.Column.text("status", "Status"))
                .column(UiTable.Column.text("userId", "User"))
                .column(UiTable.Column.date("startedAt", "Started").asSortable())
                .rowAction(UiAction.secondary("open", "Open").icon("show")
                        .onClick(trigger(on(ChatUiController.class).getSession(ROW_ID.toString(), null))))
                .rowAction(UiAction.danger("delete", "Delete").icon("delete")
                        .confirm("Delete this session?")
                        .onClick(trigger(on(AgentUiController.class)
                                .deleteSession(agent.value(), ROW_ID.toString(), null))));

        for (ManagedSession session : ofAgent) {
            table.row(Map.of(
                "id",        session.id().value(),
                "title",     session.title() != null ? session.title() : "(untitled)",
                "status",    session.status() != null ? session.status().name() : "",
                "userId",    session.userId() != null ? session.userId().value() : "",
                "startedAt", session.createdAt() != null ? DT_FMT.format(session.createdAt()) : ""
            ));
        }
        if (selectedSessionId != null) table.selectedRow(selectedSessionId.value());
        return table;
    }
}
