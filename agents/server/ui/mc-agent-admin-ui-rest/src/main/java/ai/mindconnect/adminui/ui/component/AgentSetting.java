package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.capabilities.skill.SkillPlugin;
import ai.mindconnect.agent.starter.runtime.bridge.AgentDefinitionMapper;
import ai.mindconnect.agent.tool.AgentTool;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What the agent pages show and set of a managed agent beyond its plain
 * fields: the rubric and symbol it falls back to, and what the capabilities
 * read from the definition's metadata under their own keys. In one place,
 * so that no page reads a map by a key it spelled itself.
 */
public final class AgentSetting {

    /** The rubric of an agent that names none. */
    public static final String DEFAULT_GROUP = "general";

    /** The rubric whose agents the Response Reviewers picker offers. */
    public static final String REVIEWER_GROUP = "reviewer";

    /** The symbol of an agent that names none. */
    public static final String DEFAULT_ICON = "bot";

    /**
     * Tools the runtime gives an agent by itself — the delegation tools with
     * the roster, the search with the deferred tools, the loader with the
     * skills — and that no agent lists.
     */
    public static final Set<String> DERIVED_TOOLS = Set.of(
            "run_agent", "run_agents", "list_agents", "tool_search", "skill");

    /** The skills modes of the form: every skill, none, or the ones ticked. */
    public static final String ALL = "ALL";
    public static final String NONE = "NONE";
    public static final String SPECIFIC = "SPECIFIC";

    private AgentSetting() {
    }

    public static String group(ManagedAgent agent) {
        return agent.group() == null || agent.group().isBlank() ? DEFAULT_GROUP : agent.group();
    }

    public static String icon(ManagedAgent agent) {
        return agent.icon() == null || agent.icon().isBlank() ? DEFAULT_ICON : agent.icon();
    }

    /**
     * A rubric or a symbol as it is kept: trimmed, lower-cased, blank read
     * as absent. Both are machine names typed by hand — "Assistants" would
     * otherwise open a second rubric under the same heading, and "Telescope"
     * would look up a symbol that does not exist.
     */
    public static String machineName(String typed) {
        if (typed == null) return null;
        String name = typed.trim().toLowerCase(Locale.ROOT);
        return name.isEmpty() ? null : name;
    }

    /** The agent names a capability reads under {@code key}: the roster, the reviewers. */
    public static List<String> names(AgentDefinition definition, String key) {
        return definition.metadata().get(key) instanceof List<?> named
                ? named.stream().map(String::valueOf).toList() : List.of();
    }

    /** {@link #ALL} without an entry, {@link #NONE} for an empty one, else {@link #SPECIFIC}. */
    public static String skillsMode(AgentDefinition definition) {
        if (!(definition.metadata().get(SkillPlugin.SKILLS) instanceof List<?> picked)) return ALL;
        return picked.isEmpty() ? NONE : SPECIFIC;
    }

    /** The definition with another value under {@code key}; {@code null} takes the entry away. */
    public static AgentDefinition with(AgentDefinition definition, String key, Object value) {
        Map<String, Object> metadata = new HashMap<>(definition.metadata());
        if (value == null) {
            metadata.remove(key);
        } else {
            metadata.put(key, value);
        }
        return new AgentDefinition(definition.name(), definition.description(), definition.instructions(),
                definition.modelSettings(), definition.tools(), definition.maxRounds(), metadata);
    }

    /** The agent's tools as the tool pages edit them. */
    public static List<AgentTool> tools(ManagedAgent agent) {
        return agent.definition().tools().stream().map(AgentDefinitionMapper::agentTool).toList();
    }

    /** The agent with these tools, without the ones the runtime derives. */
    public static ManagedAgent withTools(ManagedAgent agent, List<AgentTool> tools) {
        AgentDefinition definition = agent.definition();
        return agent.withDefinition(new AgentDefinition(definition.name(), definition.description(),
                definition.instructions(), definition.modelSettings(),
                tools.stream().filter(tool -> !DERIVED_TOOLS.contains(tool.name()))
                        .map(AgentDefinitionMapper::binding).toList(),
                definition.maxRounds(), definition.metadata()));
    }
}
