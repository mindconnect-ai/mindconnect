package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.AgentId;

import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ListAgentsTool implements Tool {

    private final AgentDefinitionRepository definitionRepository;
    /** The agent doing the asking — its roster decides what comes back. */
    private final AgentId callerId;

    public ListAgentsTool(AgentDefinitionRepository definitionRepository) {
        this(definitionRepository, null);
    }

    /**
     * @param callerId the calling agent, whose {@code callableAgents} roster
     *                 narrows the answer. {@code null} — or an id with no
     *                 definition behind it, as a session's inline agent has —
     *                 lists every agent.
     */
    public ListAgentsTool(AgentDefinitionRepository definitionRepository,
                          AgentId callerId) {
        this.callerId = callerId;
        this.definitionRepository = definitionRepository;
    }

    @Override
    public String name() {
        return "list_agents";
    }

    @Override
    public String description() {
        return "Lists all available agents with their names and descriptions.";
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
        List<AgentDefinition> agents = definitionRepository.findAll().stream()
                .filter(a -> caller == null || caller.mayCall(a.name()))
                .toList();
        if (agents.isEmpty()) return "No agents found.";
        return agents.stream()
                .map(a -> "- " + a.name() + ": " + a.description())
                .collect(Collectors.joining("\n"));
    }
}
