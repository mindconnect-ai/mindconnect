package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.chatui.ui.UiComponent;
import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.adminui.ui.controller.AgentUiController;

import static ai.mindconnect.chatui.ui.UiActions.ROW_ID;
import static ai.mindconnect.chatui.ui.UiActions.trigger;
import static org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder.on;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiTable;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

/**
 * Tabular view of an agent's tools. Each row has View / Edit / Delete
 * actions targeting {@code /admin/api/agents/{agentId}/tools/{toolId}}
 * (the {@code {id}} placeholder is substituted by the renderer per
 * row). Header has an "Add Tool" action.
 */
public final class ToolTableComponent implements UiComponent {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentDefinition agent;
    private final UUID selectedToolId;

    public ToolTableComponent(AgentDefinition agent) {
        this(agent, null);
    }

    public ToolTableComponent(AgentDefinition agent, UUID selectedToolId) {
        this.agent = agent;
        this.selectedToolId = selectedToolId;
    }

    @Override
    public String id() {
        return "tool-table-" + agent.id();
    }

    @Override
    public UiTable render() {
        var table = UiTable.of(id(), "Tools")
                .action(UiAction.primary("add-tool", "Add Tool").icon("add")
                        .onClick(trigger(on(AgentUiController.class).newToolForm(agent.id()))))
                .column(UiTable.Column.text("name", "Name"))
                .column(UiTable.Column.text("description", "Description"))
                .column(UiTable.Column.text("overrides", "Overrides"))
                .column(UiTable.Column.text("enabled", "Enabled"))
                .column(UiTable.Column.text("deferred", "Deferred"))
                .column(UiTable.Column.text("needsApproval", "Approval"))
                .rowAction(UiAction.secondary("view", "View").icon("show")
                        .onClick(trigger(on(AgentUiController.class).viewTool(agent.id(), ROW_ID))))
                .rowAction(UiAction.secondary("edit", "Edit").icon("edit")
                        .onClick(trigger(on(AgentUiController.class).editToolForm(agent.id(), ROW_ID))))
                .rowAction(UiAction.danger("delete", "Delete").icon("delete")
                        .confirm("Delete this tool?")
                        .onClick(trigger(on(AgentUiController.class).deleteTool(agent.id(), ROW_ID, null))));

        for (AgentTool t : agent.tools()) {
            table.row(Map.of(
                "id",          t.id().toString(),
                "name",        t.name(),
                "description", t.description() != null ? t.description() : "",
                "overrides",   overridesSummary(t),
                "enabled",     t.enabled() ? "✓" : "—",
                "deferred",    t.deferred() ? "✓" : "—",
                "needsApproval", t.needsApproval() ? "✓" : "—"
            ));
        }
        if (selectedToolId != null) table.selectedRow(selectedToolId.toString());
        return table;
    }

    private static String overridesSummary(AgentTool t) {
        if (t.overrides() == null || t.overrides().isEmpty()) return "";
        try { return MAPPER.writeValueAsString(t.overrides()); }
        catch (JsonProcessingException e) { return "…"; }
    }
}
