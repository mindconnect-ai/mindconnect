package ai.mindconnect.agent.runtime.capabilities.subagent;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import ai.mindconnect.agent.loop.domain.ContentPart;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.SessionSpec;
import ai.mindconnect.agent.runtime.api.domain.ToolCall;
import ai.mindconnect.agent.runtime.api.domain.ToolDefinition;
import ai.mindconnect.agent.runtime.api.domain.ToolOutput;
import ai.mindconnect.agent.runtime.api.domain.TurnRequest;
import ai.mindconnect.agent.runtime.api.port.in.SessionService;
import ai.mindconnect.agent.runtime.api.port.out.Tool;

/**
 * An agent, offered to another agent as a tool. Called with a task, it opens
 * a child session and hands the call to a turn there; running that turn and
 * bringing its answer back is the engine's part.
 */
final class SubAgentTool implements Tool {

    static final String TASK = "task";
    private static final Map<String, Object> PARAMETERS = Map.of(
            "type", "object",
            "properties", Map.of(TASK, Map.of(
                    "type", "string",
                    "description", "What the agent should do, with everything it needs to know: "
                            + "it sees nothing of this conversation.")),
            "required", List.of(TASK));

    private final AgentDefinition agent;
    private final Supplier<SessionService> sessions;

    SubAgentTool(AgentDefinition agent, Supplier<SessionService> sessions) {
        this.agent = agent;
        this.sessions = sessions;
    }

    static ToolDefinition definition(AgentDefinition agent) {
        return new ToolDefinition(agent.name(), agent.description(), PARAMETERS);
    }

    @Override
    public ToolDefinition definition() {
        return definition(agent);
    }

    @Override
    public ToolOutput execute(ToolCall call, Session session) {
        if (!(call.arguments().get(TASK) instanceof String task) || task.isBlank()) {
            return ToolOutput.failed("Error: " + agent.name() + " needs a task");
        }
        // A session of its own: the sub-agent starts from the task alone, and
        // its messages do not land in the caller's history. The metadata
        // go along — it works in the same place for the same user.
        Session child = sessions.get().open(new SessionSpec(
                session.userId(), session.id(), agent, session.metadata()));
        return ToolOutput.delegate(new TurnRequest(
                child.id(), List.of(new ContentPart.Text(task)), agent));
    }
}
