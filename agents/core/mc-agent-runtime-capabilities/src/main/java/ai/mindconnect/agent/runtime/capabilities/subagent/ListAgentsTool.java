package ai.mindconnect.agent.runtime.capabilities.subagent;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.ToolCall;
import ai.mindconnect.agent.runtime.api.domain.ToolDefinition;
import ai.mindconnect.agent.runtime.api.domain.ToolOutput;
import ai.mindconnect.agent.runtime.api.port.out.AgentResolver;
import ai.mindconnect.agent.runtime.api.port.out.Tool;

/** Tells an agent which agents it can hand a task to, and what each is for. */
final class ListAgentsTool implements Tool {

    static final String NAME = "list_agents";

    static final ToolDefinition DEFINITION = new ToolDefinition(NAME,
            "Lists the agents you can call with run_agent, with their names and descriptions.",
            Map.of("type", "object", "properties", Map.of(), "required", List.of()));

    private final AgentDefinition caller;
    private final AgentResolver agents;

    ListAgentsTool(AgentDefinition caller, AgentResolver agents) {
        this.caller = caller;
        this.agents = agents;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolOutput execute(ToolCall call, Session session) {
        return ToolOutput.ok(SubAgentPlugin.subAgentsOf(caller).stream()
                .map(name -> agents.resolve(name, session))
                .flatMap(java.util.Optional::stream)
                .map(agent -> "- " + agent.name() + ": " + agent.description())
                .collect(Collectors.joining("\n")));
    }
}
