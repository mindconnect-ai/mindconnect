package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.chatui.ui.UiComponent;
import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.adminui.ui.controller.AgentUiController;

import static ai.mindconnect.chatui.ui.UiActions.ROW_ID;
import static ai.mindconnect.chatui.ui.UiActions.trigger;
import static org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder.on;
import ai.mindconnect.agent.port.out.AgentSessionRepository;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiDetail;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiLink;
import ai.mindconnect.ui.model.UiList;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiStack;
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
    public UiNode render() {
        var details = buildDetailsTab();

        // selectedRow applies only to whichever section it was deep-linked for.
        UUID selectedToolId = parseUuid("tools".equals(initialSection) ? selectedRowId : null);
        UUID selectedSessId = parseUuid("sessions".equals(initialSection) ? selectedRowId : null);

        var toolTable    = new ToolTableComponent(agent, selectedToolId).render();
        var sessionTable = new SessionTableComponent(agent, userId, sessionRepository, selectedSessId).render();

        // The section's own title is a plain string — the renderer escapes it,
        // so an icon cannot go in it. A header-only UiList draws the same bar
        // the agent list and the workflow detail page carry, and that one does
        // take an icon. The section then goes untitled: with the bar above it,
        // a title would say the agent's name twice in a row.
        var header = UiList.of(id() + "-header", agent.name()).icon(agent.iconOrDefault());

        var section = UiSection.of(id(), null)
                .section("details",  "Details", details)
                .section("tools",    "Tools (" + agent.tools().size() + ")", toolTable)
                .section("sessions", "Sessions", sessionTable);
        if (initialSection != null) section.initialSection(initialSection);

        return UiStack.of(id() + "-page").child(header).child(section);
    }

    private UiDetail buildDetailsTab() {
        return UiDetail.of("agent-detail-" + agent.id(), agent.name())
                .field(UiField.text("name", "Name", agent.name()))
                .field(UiField.text("description", "Description", agent.description()))
                // Capitalised like the list heading it corresponds to — this
                // view only reads. The edit form shows the stored machine name.
                .field(UiField.text("group", "Group",
                        ToolCatalogComponent.displayGroup(agent.groupOrDefault())))
                // The name only. UiDetail does not draw a field's icon, and
                // the header above the tabs already shows the symbol itself.
                .field(UiField.text("icon", "Icon", agent.iconOrDefault()))
                .field(UiField.text("callableAgents", "Callable Agents", callableAgentsSummary()))
                .field(UiField.text("llmConfigName", "LLM Config", agent.llmConfigName()))
                .field(UiField.text("status", "Status",
                        agent.status() != null ? agent.status().name() : null))
                .field(UiField.number("maxIterations", "Max Iterations", agent.maxIterations()))
                .field(UiField.text("memory", "Memory", memorySummary()))
                .action(UiAction.primary("edit", "Edit").icon("edit")
                        .onClick(trigger(on(AgentUiController.class).editForm(agent.id()))))
                // No back link: "Agents" in the sidebar is the way back now —
                // a link squeezed between the buttons just read as a third,
                // differently-styled action.
                .action(UiAction.danger("delete", "Delete").icon("delete")
                        .confirm("Delete agent '" + agent.name() + "'?")
                        .onClick(trigger(on(AgentUiController.class).delete(agent.id()))));
    }

    /** The roster, or the word for having none — which means all of them. */
    private String callableAgentsSummary() {
        var roster = agent.effectiveCallableAgents();
        return roster.isEmpty() ? "all agents" : String.join(", ", roster);
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
