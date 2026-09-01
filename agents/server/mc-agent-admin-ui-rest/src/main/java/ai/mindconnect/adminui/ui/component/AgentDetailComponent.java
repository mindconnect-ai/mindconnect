package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.chatui.ui.UiComponent;
import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.port.out.AgentSessionRepository;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiDetail;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiLink;
import ai.mindconnect.ui.model.UiSection;

import java.util.UUID;

/**
 * Tabbed detail view for a single agent: Details / Tools / Sessions.
 * The Tools and Sessions tabs are full-fat {@link ToolTableComponent}
 * and {@link SessionTableComponent} instances; the Details tab is a
 * compact {@link UiDetail} built inline.
 *
 * <p>The {@code initialSection} parameter and {@code selectedRowId}
 * let the controller deep-link to e.g. "open this agent on the Tools
 * tab with row X highlighted" — used after an add/edit operation so
 * the user returns to the row they just touched.
 */
public final class AgentDetailComponent implements UiComponent {

    private final AgentDefinition agent;
    private final String userId;
    private final AgentSessionRepository sessionRepository;
    private final String initialSection;
    private final String selectedRowId;

    public AgentDetailComponent(AgentDefinition agent, String userId,
                                 AgentSessionRepository sessionRepository) {
        this(agent, userId, sessionRepository, null, null);
    }

    public AgentDetailComponent(AgentDefinition agent, String userId,
                                 AgentSessionRepository sessionRepository,
                                 String initialSection, String selectedRowId) {
        this.agent = agent;
        this.userId = userId;
        this.sessionRepository = sessionRepository;
        this.initialSection = initialSection;
        this.selectedRowId = selectedRowId;
    }

    @Override
    public String id() {
        return "agent-section-" + agent.id();
    }

    @Override
    public UiSection render() {
        var details = buildDetailsTab();

        // selectedRow applies only to whichever section it was deep-linked for.
        UUID selectedToolId = parseUuid("tools".equals(initialSection) ? selectedRowId : null);
        UUID selectedSessId = parseUuid("sessions".equals(initialSection) ? selectedRowId : null);

        var toolTable    = new ToolTableComponent(agent, selectedToolId).render();
        var sessionTable = new SessionTableComponent(agent, userId, sessionRepository, selectedSessId).render();

        var section = UiSection.of(id(), agent.name())
                .section("details",  "Details", details)
                .section("tools",    "Tools (" + agent.tools().size() + ")", toolTable)
                .section("sessions", "Sessions", sessionTable);
        if (initialSection != null) section.initialSection(initialSection);
        return section;
    }

    private UiDetail buildDetailsTab() {
        return UiDetail.of("agent-detail-" + agent.id(), agent.name())
                .field(UiField.text("name", "Name", agent.name()))
                .field(UiField.text("description", "Description", agent.description()))
                // Capitalised like the list heading it corresponds to — this
                // view only reads. The edit form shows the stored machine name.
                .field(UiField.text("group", "Group",
                        ToolCatalogComponent.displayGroup(agent.groupOrDefault())))
                .field(UiField.text("icon", "Icon", agent.iconOrDefault())
                        .icon(agent.iconOrDefault()))
                .field(UiField.text("llmConfigName", "LLM Config", agent.llmConfigName()))
                .field(UiField.text("status", "Status",
                        agent.status() != null ? agent.status().name() : null))
                .field(UiField.number("maxIterations", "Max Iterations", agent.maxIterations()))
                .field(UiField.text("memory", "Memory", memorySummary()))
                .action(UiAction.primary("edit", "Edit").icon("edit")
                        .dispatch("GET", "/admin/api/agents/" + agent.id() + "/edit"))
                // No back link: "Agents" in the sidebar is the way back now —
                // a link squeezed between the buttons just read as a third,
                // differently-styled action.
                .action(UiAction.danger("delete", "Delete").icon("delete")
                        .confirm("Delete agent '" + agent.name() + "'?")
                        .dispatch("DELETE", "/admin/api/agents/" + agent.id()));
    }

    /** The effective strategy kind, plus the compression switch where it exists. */
    private String memorySummary() {
        var config = agent.effectiveMemoryConfig();
        if (config instanceof ai.mindconnect.agent.memory.domain.SummarizingWindowConfig sw) {
            return config.kind() + " (tool-result compression "
                    + (sw.compressToolResults() ? "on" : "off") + ")";
        }
        return config.kind();
    }

    private static UUID parseUuid(String s) {
        if (s == null) return null;
        try { return UUID.fromString(s); }
        catch (IllegalArgumentException e) { return null; }
    }
}
