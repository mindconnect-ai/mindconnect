package ai.mindconnect.agent.runtime.skill;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.agent.tool.ToolFactory;

import java.util.List;
import java.util.Map;

/**
 * Builds the {@code skill} tool for one call.
 *
 * <p>The tool is not one an operator assigns: the runtime adds it to an
 * agent that has skills switched on and takes it away again when it has
 * not, carrying the agent's skill names as overrides so no definition has
 * to be looked up here. Unavailable without a catalog — a host that wires
 * none simply has no skills.
 */
public final class SkillToolFactory implements ToolFactory {

    /** The overrides key carrying the names the agent's setting names. */
    public static final String NAMES = "skills";

    private SkillCatalog catalog;

    @Override public String name() { return SkillTool.NAME; }

    @Override public String group() { return "skills"; }

    @Override
    public void bind(ToolEnvironment env) {
        this.catalog = env.get(SkillCatalog.class).orElse(null);
    }

    @Override
    public boolean isAvailable() { return catalog != null; }

    @Override
    public Map<String, Object> overridesSchema() {
        return Map.of("type", "object", "properties", Map.of(
                NAMES, Map.of("type", "array", "items", Map.of("type", "string"),
                        "description", "The skills this agent may load; empty means every skill "
                                + "the installation, the user and the project have.")));
    }

    @Override
    public Tool create(AgentTool agentTool, ToolCallScope scope) {
        return new SkillTool(catalog, names(agentTool), scope.userId(), scope.workingDir());
    }

    /** The names the binding carries, as strings; empty when it carries none. */
    private static List<String> names(AgentTool agentTool) {
        Object raw = agentTool == null || agentTool.overrides() == null
                ? null : agentTool.overrides().get(NAMES);
        if (!(raw instanceof List<?> list)) return List.of();
        return list.stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList();
    }
}
