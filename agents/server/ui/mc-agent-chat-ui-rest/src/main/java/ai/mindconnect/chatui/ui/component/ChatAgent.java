package ai.mindconnect.chatui.ui.component;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.management.api.AgentManagement;
import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.management.api.ManagedSession;
import ai.mindconnect.agent.management.api.SessionManagement;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.domain.ToolBinding;
import ai.mindconnect.agent.runtime.capabilities.subagent.SubAgentPlugin;
import ai.mindconnect.common.DomainException;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What the chat reads and sets of the agent a chat runs on: the definition
 * its next turn uses, and what the capabilities keep in that definition's
 * metadata under their own keys. In one place, so that no component reads a
 * map by a key it spelled itself.
 */
public final class ChatAgent {

    /** The symbol of an agent that names none. */
    public static final String DEFAULT_ICON = "bot";

    /**
     * Tools the runtime gives an agent by itself — the delegation tools with
     * the roster, the search with the deferred tools, the loader with the
     * skills — and that no chat switches.
     */
    public static final Set<String> DERIVED_TOOLS = Set.of(
            "run_agent", "run_agents", "list_agents", "tool_search", "skill");

    private ChatAgent() {
    }

    /**
     * The agent the chat's next turn runs on: the stored agent it refers to,
     * or the definition of its own under the id and the looks of the stored
     * agent it was taken from.
     */
    public static ManagedAgent effective(SessionManagement sessions, AgentManagement agents, ManagedSession chat) {
        AgentDefinition runs = sessions.agentOf(chat.id()).orElseThrow(() -> DomainException.notFound(
                "AgentDefinition", chat.agentId().map(id -> id.value()).orElse(chat.id().value())));
        String id = runs.metadata().get(ManagedAgent.AGENT_ID) instanceof String stored ? stored
                : chat.agentId().map(named -> named.value()).orElse(null);
        Optional<ManagedAgent> origin = id == null ? Optional.empty()
                : agents.find(AgentId.of(id));
        Instant now = Instant.now();
        return new ManagedAgent(id == null ? null : AgentId.of(id), runs,
                origin.map(ManagedAgent::group).orElse(null), origin.map(ManagedAgent::icon).orElse(null),
                origin.map(ManagedAgent::welcomeMessage).orElse(null), ManagedAgent.Status.ACTIVE, now, now, 1);
    }

    /**
     * The agent with its id in its definition, where the tools read whose
     * agent calls them — for a definition a chat keeps for itself, which no
     * agent management writes.
     */
    public static ManagedAgent identified(ManagedAgent agent) {
        if (agent.id() == null) {
            return agent;
        }
        AgentDefinition definition = agent.definition();
        java.util.Map<String, Object> metadata = new java.util.HashMap<>(definition.metadata());
        metadata.put(ManagedAgent.AGENT_ID, agent.id().value());
        return agent.withDefinition(new AgentDefinition(definition.name(), definition.description(),
                definition.instructions(), definition.modelSettings(), definition.tools(), definition.maxRounds(),
                metadata));
    }

    /** The agent's id as the chat's links and forms carry it; {@code null} for an agent without one. */
    public static AgentId id(ManagedAgent agent) {
        return agent == null ? null : agent.id();
    }

    /** The rubric of an agent that names none. */
    public static final String DEFAULT_GROUP = "general";

    public static String group(ManagedAgent agent) {
        return agent.group() == null || agent.group().isBlank() ? DEFAULT_GROUP : agent.group();
    }

    public static String icon(ManagedAgent agent) {
        return agent.icon() == null || agent.icon().isBlank() ? DEFAULT_ICON : agent.icon();
    }

    /** The name of the LLM configuration the agent runs on; {@code null} for the installation's default. */
    public static String model(ManagedAgent agent) {
        return agent.definition().modelSettings().model();
    }

    /** The agents this one may hand work to, empty when it may call nobody. */
    public static List<String> roster(ManagedAgent agent) {
        return agent.definition().metadata().get(SubAgentPlugin.SUB_AGENTS) instanceof List<?> named
                ? named.stream().map(String::valueOf).toList() : List.of();
    }

    /** The definition on another prompt and another model, everything else as it is. */
    public static AgentDefinition withPromptAndModel(AgentDefinition definition, String prompt, String model) {
        return new AgentDefinition(definition.name(), definition.description(), prompt,
                // Another model keeps what else the settings say: a temperature is not the model's.
                new ModelSettings(model, definition.modelSettings().temperature(),
                        definition.modelSettings().maxOutputTokens(), definition.modelSettings().reasoningEffort(),
                        definition.modelSettings().extra()),
                definition.tools(), definition.maxRounds(), definition.metadata());
    }

    public static AgentDefinition withTools(AgentDefinition definition, List<ToolBinding> tools) {
        return new AgentDefinition(definition.name(), definition.description(), definition.instructions(),
                definition.modelSettings(), tools, definition.maxRounds(), definition.metadata());
    }

    public static AgentDefinition withRoster(AgentDefinition definition, List<String> names) {
        Map<String, Object> metadata = new HashMap<>(definition.metadata());
        metadata.put(SubAgentPlugin.SUB_AGENTS, List.copyOf(names));
        return new AgentDefinition(definition.name(), definition.description(), definition.instructions(),
                definition.modelSettings(), definition.tools(), definition.maxRounds(), metadata);
    }
}
