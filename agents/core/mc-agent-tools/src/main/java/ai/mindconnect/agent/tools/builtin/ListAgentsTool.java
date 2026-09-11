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
    /** The session's working directory, whose project may define agents of its own. */
    private final String workingDir;

    public ListAgentsTool(AgentDefinitionRepository definitionRepository) {
        this(definitionRepository, null, null);
    }

    public ListAgentsTool(AgentDefinitionRepository definitionRepository, AgentId callerId) {
        this(definitionRepository, callerId, null);
    }

    /**
     * @param callerId the calling agent, whose {@code callableAgents} roster
     *                 narrows the answer. {@code null} — or an id with no
     *                 definition behind it, as a session's inline agent has —
     *                 lists every agent.
     * @param workingDir the session's working directory; the agents its
     *                 project defines are listed first, {@code null} for none.
     */
    public ListAgentsTool(AgentDefinitionRepository definitionRepository,
                          AgentId callerId, String workingDir) {
        this.callerId = callerId;
        this.definitionRepository = definitionRepository;
        this.workingDir = workingDir;
    }

    @Override
    public String name() {
        return "list_agents";
    }

    @Override
    public String description() {
        return "Lists the agents you can call with run_agent, with their names and descriptions: "
                + "the ones registered on this server, and the ones this project defines in "
                + ai.mindconnect.agent.runtime.service.agents.ProjectAgents.DIR + ".";
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
        // The project's own come first: they are the ones written for the
        // code in front of you, and they shadow a registered agent of the
        // same name when run_agent resolves it.
        var project = ai.mindconnect.agent.runtime.service.agents.ProjectAgents.list(workingDir);
        List<String> lines = new java.util.ArrayList<>();
        for (var a : project) {
            lines.add("- " + a.name() + " (this project): "
                    + (a.description().isBlank() ? "no description" : a.description()));
        }
        java.util.Set<String> shadowed = project.stream()
                .map(a -> a.name().toLowerCase(java.util.Locale.ROOT))
                .collect(Collectors.toSet());
        for (AgentDefinition a : agents) {
            if (shadowed.contains(a.name().toLowerCase(java.util.Locale.ROOT))) continue;
            lines.add("- " + a.name() + ": " + a.description());
        }
        if (lines.isEmpty()) return "No agents found.";
        return String.join("\n", lines);
    }
}
