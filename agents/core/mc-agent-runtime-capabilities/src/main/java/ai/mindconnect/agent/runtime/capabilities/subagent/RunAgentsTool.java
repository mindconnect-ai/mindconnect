package ai.mindconnect.agent.runtime.capabilities.subagent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.ToolCall;
import ai.mindconnect.agent.runtime.api.domain.ToolDefinition;
import ai.mindconnect.agent.runtime.api.domain.ToolOutput;
import ai.mindconnect.agent.runtime.api.domain.TurnRequest;
import ai.mindconnect.agent.runtime.api.port.out.Tool;

/**
 * Hands several independent tasks to sub-agents at once. The engine runs
 * them side by side and the call's result is all their answers together —
 * without depending on the model asking for several tools in one round.
 */
final class RunAgentsTool implements Tool {

    static final String NAME = "run_agents";
    private static final String TASKS = "tasks";

    static final ToolDefinition DEFINITION = new ToolDefinition(NAME,
            "Delegates several INDEPENDENT tasks to agents in parallel and returns all their results together. "
                    + "Each task is {\"name\": \"<agent>\", \"message\": \"<self-contained task>\"}. "
                    + "Use this instead of calling run_agent many times when the sub-tasks do not depend on each "
                    + "other — they execute concurrently, so it is much faster. For a single task, or when one "
                    + "task needs another's output, use run_agent.",
            Map.of(
                    "type", "object",
                    "properties", Map.of(TASKS, Map.of(
                            "type", "array",
                            "description", "The independent tasks to run in parallel. Each runs as a fresh sub-agent.",
                            "items", Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                            RunAgentTool.AGENT, Map.of("type", "string",
                                                    "description", "Name of the agent to call, e.g. \"explorer\" "
                                                            + "or \"web-researcher\""),
                                            RunAgentTool.MESSAGE, Map.of("type", "string",
                                                    "description", "The complete, self-contained task for this agent")),
                                    "required", List.of(RunAgentTool.AGENT, RunAgentTool.MESSAGE)))),
                    "required", List.of(TASKS)));

    private final RunAgentTool single;

    RunAgentsTool(RunAgentTool single) {
        this.single = single;
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolOutput execute(ToolCall call, Session session) {
        if (!(call.arguments().get(TASKS) instanceof List<?> tasks) || tasks.isEmpty()) {
            return ToolOutput.failed("Error: " + NAME + " needs at least one task");
        }
        // Every task is checked before any session is opened for one: a batch runs whole or not at all.
        try {
            List<TurnRequest> requests = new ArrayList<>();
            for (Object task : tasks) {
                requests.add(single.request(task instanceof Map<?, ?> named ? named : Map.of(), session));
            }
            return ToolOutput.delegate(requests);
        } catch (IllegalArgumentException e) {
            return ToolOutput.failed("Error: " + e.getMessage());
        }
    }
}
