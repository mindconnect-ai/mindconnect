package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.chatui.ui.UiComponent;
import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.adminui.ui.controller.AgentToolUiController;

import static ai.mindconnect.ui.mvc.UiActions.trigger;
import static org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder.on;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.agent.runtime.capabilities.subagent.SubAgentPlugin;
import ai.mindconnect.jackson.JsonMapperFactory;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiDetail;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiLink;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiStack;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Read-only detail view of one agent's tool. Shows the tool's name,
 * description, overrides (as pretty-printed JSON) and — when the
 * {@link ToolRegistry} can resolve the tool — its parameters as a
 * readable table. Header has
 * an Edit action and a Back link that returns to the agent's Tools
 * tab with this row pre-selected.
 */
public final class ToolDetailComponent implements UiComponent {

    private static final ObjectMapper MAPPER = JsonMapperFactory.create();

    private final ManagedAgent agent;
    private final AgentTool tool;
    private final ToolRegistry toolRegistry;

    public ToolDetailComponent(ManagedAgent agent, AgentTool tool, ToolRegistry toolRegistry) {
        this.agent = agent;
        this.tool = tool;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public String id() {
        return "tool-detail-" + tool.id().value();
    }

    @Override
    public UiNode render() {
        String overridesJson = "";
        if (tool.overrides() != null && !tool.overrides().isEmpty()) {
            try { overridesJson = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(tool.overrides()); }
            catch (JsonProcessingException ignored) {}
        }

        // Schema comes from the registry, or — for inline tools (run_agent /
        // run_agents, brought by the sub-agent plugin) — from its definitions.
        Object schema = null;
        var resolved = toolRegistry.resolve(tool, ToolCallScope.detached(null));
        if (resolved.isPresent()) {
            schema = resolved.get().parametersSchema();
        } else {
            schema = SubAgentPlugin.delegationTools().stream()
                    .filter(d -> d.name().equals(tool.name()))
                    .map(d -> (Object) d.parametersSchema())
                    .findFirst().orElse(null);
        }
        var detail = UiDetail.of(id(), tool.name())
                .field(UiField.text("name",        "Name",        tool.name()))
                .field(UiField.text("description", "Description", tool.description()))
                .field(UiField.textarea("overrides", "Overrides (JSON)", overridesJson))
                .action(UiAction.primary("edit", "Edit").icon("edit")
                        .onClick(trigger(on(AgentToolUiController.class).editToolForm(agent.id().value(), tool.id().value()))))
                .action(UiAction.secondary("test", "Test").icon("flash")
                        .onClick(trigger(on(AgentToolUiController.class).testToolDialog(agent.id().value(), tool.id().value()))))
                .link(UiLink.of("back",
                        "/admin/agents/" + agent.id().value() + "?section=tools&row=" + tool.id().value(),
                        "← Back to Agent"));

        // The parameters the LLM will actually be offered (pinned params are
        // already stripped by the resolve path), plus the config overrides the
        // tool declares — both as readable tables.
        UiNode schemaTable = ToolSchemaTable.render(id() + "-schema", schema);
        UiNode overridesTable = ToolSchemaTable.render(id() + "-ov-schema",
                toolRegistry.overridesSchema(tool.name()), "Config Overrides");
        if (schemaTable == null && overridesTable == null) {
            return detail;
        }
        UiStack stack = UiStack.of(id() + "-wrap").gap(16).child(detail);
        if (schemaTable != null) stack.child(schemaTable);
        if (overridesTable != null) stack.child(overridesTable);
        return stack;
    }
}
