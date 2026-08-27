package ai.mindconnect.agent.tools.builtin;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.port.out.AgentDefinitionRepository;
import ai.mindconnect.common.Namespace;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ListAgentsTool implements Tool {

    private final AgentDefinitionRepository definitionRepository;
    private final Namespace namespace;

    public ListAgentsTool(AgentDefinitionRepository definitionRepository, Namespace namespace) {
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
        List<AgentDefinition> agents = definitionRepository.findByNamespace(namespace);
        if (agents.isEmpty()) return "No agents found in namespace " + namespace.value();
        return agents.stream()
                .map(a -> "- " + a.name() + ": " + a.description())
                .collect(Collectors.joining("\n"));
    }
}
