package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.port.out.AgentDefinitionRepository;
import ai.mindconnect.common.Namespace;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class ListAgentsTool implements Tool {

    private final AgentDefinitionRepository definitionRepository;
    private final Namespace namespace;
    /** The agent doing the asking — its roster decides what comes back. */
    private final UUID callerId;

    public ListAgentsTool(AgentDefinitionRepository definitionRepository, Namespace namespace) {
        this(definitionRepository, namespace, null);
    }

    /**
     * @param callerId the calling agent, whose {@code callableAgents} roster
     *                 narrows the answer. {@code null} — or an id with no
     *                 definition behind it, as a session's inline agent has —
     *                 lists the whole namespace.
     */
    public ListAgentsTool(AgentDefinitionRepository definitionRepository, Namespace namespace,
                          UUID callerId) {
        this.callerId = callerId;
        this.definitionRepository = definitionRepository;
        this.namespace = namespace;
    }

    @Override
    public String name() {
        return "list_agents";
    }

    @Override
    public String description() {
        return "Lists all available agents in the current namespace with their names and descriptions.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(),
                "required", new String[0]
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        // The roster lives on the calling agent and the rule for reading it
        // lives on AgentDefinition — the same mayCall a run_agent is checked
        // against, so the list can never offer what the call would refuse.
        AgentDefinition caller = callerId == null
                ? null : definitionRepository.findById(callerId).orElse(null);
        List<AgentDefinition> agents = definitionRepository.findByNamespace(namespace).stream()
                .filter(a -> caller == null || caller.mayCall(a.name()))
                .toList();
        if (agents.isEmpty()) return "No agents found in namespace " + namespace.value();
        return agents.stream()
                .map(a -> "- " + a.name() + ": " + a.description())
                .collect(Collectors.joining("\n"));
    }
}
