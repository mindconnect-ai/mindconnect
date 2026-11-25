package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.chatui.ui.UiComponent;
import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.capabilities.memory.MemoryPlugin;
import ai.mindconnect.agent.runtime.capabilities.skill.SkillPlugin;
import ai.mindconnect.agent.runtime.capabilities.subagent.SubAgentPlugin;
import ai.mindconnect.adminui.ui.controller.AgentUiController;
import java.util.List;

import static ai.mindconnect.ui.mvc.UiActions.trigger;
import static org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder.on;
import ai.mindconnect.agent.management.api.SessionManagement;
import ai.mindconnect.jackson.JsonMapperFactory;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiDetail;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiList;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiStack;
import ai.mindconnect.ui.model.UiSection;

import ai.mindconnect.agent.EntityId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.tool.AgentToolId;

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

    private final ManagedAgent agent;
    /** What the runtime runs: name, model, tools, and in its metadata what the capabilities read. */
    private final AgentDefinition definition;
    private final String userId;
    private final SessionManagement sessions;
    private final String initialSection;
    private final String selectedRowId;
    private final JsonMapperFactory json;
    /** The "Memory" tab — what this agent keeps about its users; null leaves it out. */
    private UiNode memoryTab;

    public AgentDetailComponent(ManagedAgent agent, String userId,
                                 SessionManagement sessions, JsonMapperFactory json) {
        this(agent, userId, sessions, null, null, json);
    }

    public AgentDetailComponent(ManagedAgent agent, String userId,
                                 SessionManagement sessions,
                                 String initialSection, String selectedRowId, JsonMapperFactory json) {
        this.agent = agent;
        this.definition = agent.definition();
        this.userId = userId;
        this.sessions = sessions;
        this.initialSection = initialSection;
        this.selectedRowId = selectedRowId;
        this.json = json;
    }

    /** Adds the "Memory" tab; {@code null} leaves it out. */
    public AgentDetailComponent memoryTab(UiNode memory) {
        this.memoryTab = memory;
        return this;
    }

    @Override
    public String id() {
        return "agent-section-" + agent.id().value();
    }

    @Override
    public UiNode render() {
        var details = buildDetailsTab();

        // selectedRow applies only to whichever section it was deep-linked for.
        AgentToolId selectedToolId = "tools".equals(initialSection) && isIdValue(selectedRowId)
                ? AgentToolId.of(selectedRowId) : null;
        SessionId selectedSessId = "sessions".equals(initialSection) && isIdValue(selectedRowId)
                ? SessionId.of(selectedRowId) : null;

        var toolTable    = new ToolTableComponent(agent, selectedToolId, json).render();
        var sessionTable = new SessionTableComponent(agent.id(), userId,
                sessions, selectedSessId).render();

        // The section's own title is a plain string — the renderer escapes it,
        // so an icon cannot go in it. A header-only UiList draws the same bar
        // the agent list and the workflow detail page carry, and that one does
        // take an icon. The section then goes untitled: with the bar above it,
        // a title would say the agent's name twice in a row.
        var header = UiList.of(id() + "-header", definition.name()).icon(AgentSetting.icon(agent));

        var section = UiSection.of(id(), null)
                .section("details",  "Details", details)
                .section("tools",    "Tools (" + definition.tools().size() + ")", toolTable)
                .section("sessions", "Sessions", sessionTable);
        if (memoryTab != null) section.section("memory", "Memory", memoryTab);
        if (initialSection != null) section.initialSection(initialSection);

        return UiStack.of(id() + "-page").child(header).child(section);
    }

    private UiDetail buildDetailsTab() {
        return UiDetail.of("agent-detail-" + agent.id().value(), definition.name())
                .field(UiField.text("name", "Name", definition.name()))
                .field(UiField.text("description", "Description", definition.description()))
                // Capitalised like the list heading it corresponds to — this
                // view only reads. The edit form shows the stored machine name.
                .field(UiField.text("group", "Group",
                        ToolCatalogComponent.displayGroup(AgentSetting.group(agent))))
                // The name only. UiDetail does not draw a field's icon, and
                // the header above the tabs already shows the symbol itself.
                .field(UiField.text("icon", "Icon", AgentSetting.icon(agent)))
                .field(UiField.text("callableAgents", "Callable Agents", callableAgentsSummary()))
                .field(UiField.text(SubAgentPlugin.CALLABLE, "Callable by other agents",
                        SubAgentPlugin.callable(definition) ? "yes" : "no"))
                .field(UiField.text("skills", "Skills", skillsSummary()))
                .field(UiField.text("llmConfigName", "LLM Config", definition.modelSettings().model()))
                .field(UiField.text("status", "Status",
                        agent.status() != null ? agent.status().name() : null))
                .field(UiField.number("maxIterations", "Max Iterations", definition.maxRounds()))
                .field(UiField.text("memory", "Memory", memorySummary()))
                .action(UiAction.primary("edit", "Edit").icon("edit")
                        .onClick(trigger(on(AgentUiController.class).editForm(agent.id().value()))))
                // No back link: "Agents" in the sidebar is the way back now —
                // a link squeezed between the buttons just read as a third,
                // differently-styled action.
                .action(UiAction.danger("delete", "Delete").icon("delete")
                        .confirm("Delete agent '" + definition.name() + "'?")
                        .onClick(trigger(on(AgentUiController.class).delete(agent.id().value()))));
    }

    /** Which skills the agent may load: none, all, or the ones named. */
    private String skillsSummary() {
        return switch (AgentSetting.skillsMode(definition)) {
            case AgentSetting.NONE -> "none";
            case AgentSetting.ALL -> "all skills";
            default -> String.join(", ", SkillPlugin.skillsOf(definition).orElse(List.of()));
        };
    }

    /** The roster, or the word for having none — which means no other agent. */
    private String callableAgentsSummary() {
        var roster = SubAgentPlugin.subAgentsOf(definition);
        return roster.isEmpty() ? "none" : String.join(", ", roster);
    }

    /** The strategy the agent names, or the one an agent that names none gets. */
    private String memorySummary() {
        return definition.metadata().get(MemoryPlugin.MEMORY) instanceof java.util.Map<?, ?> memory
                && memory.get("strategy") != null ? String.valueOf(memory.get("strategy")) : "window (default)";
    }

    /** Whether a deep-linked row is a well-formed id value; anything else selects nothing. */
    private static boolean isIdValue(String s) {
        return s != null && EntityId.VALUE.matcher(s).matches();
    }
}
