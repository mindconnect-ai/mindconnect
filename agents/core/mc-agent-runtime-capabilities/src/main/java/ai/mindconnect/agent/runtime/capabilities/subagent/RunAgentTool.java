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
import ai.mindconnect.agent.runtime.api.port.out.AgentResolver;
import ai.mindconnect.agent.runtime.api.port.out.Tool;

/**
 * Hands one task to one of the agents the caller names as its sub-agents.
 * The engine runs the task as a turn of its own, and that turn's answer is
 * the call's result.
 */
final class RunAgentTool implements Tool {

    static final String NAME = "run_agent";
    static final String AGENT = "name";
    static final String MESSAGE = "message";

    private static final String DESCRIPTION = "Delegates a task to a specialist agent and returns its answer. "
            + "Each starts with no memory of this chat, so put everything it needs in the message. NOT for "
            + "reading a file attached to this chat (vector_search) or a file you already have a path for "
            + "(file_read / document tools).";

    private static final Map<String, Object> PARAMETERS = Map.of(
            "type", "object",
            "properties", Map.of(
                    AGENT, Map.of("type", "string",
                            "description", "The name of the agent to call, e.g. \"web-researcher\" or \"file-finder\""),
                    MESSAGE, Map.of("type", "string",
                            "description", "The task or question to send to the agent")),
            "required", List.of(AGENT, MESSAGE));

    private final AgentDefinition caller;
    private final AgentResolver agents;
    private final Supplier<SessionService> sessions;

    RunAgentTool(AgentDefinition caller, AgentResolver agents, Supplier<SessionService> sessions) {
        this.caller = caller;
        this.agents = agents;
        this.sessions = sessions;
    }

    /** With the names in it: a model reads a tool's description far more reliably than it calls list_agents. */
    static ToolDefinition definition(AgentDefinition caller) {
        return new ToolDefinition(NAME, DESCRIPTION + " Available to you: "
                + String.join(", ", SubAgentPlugin.subAgentsOf(caller)) + ".", PARAMETERS);
    }

    @Override
    public ToolDefinition definition() {
        return definition(caller);
    }

    @Override
    public ToolOutput execute(ToolCall call, Session session) {
        try {
            return ToolOutput.delegate(request(call.arguments(), session));
        } catch (IllegalArgumentException e) {
            return ToolOutput.failed("Error: " + e.getMessage());
        }
    }

    /**
     * The turn that does one task, in a session of its own: the sub-agent
     * starts from the task alone, and its messages do not land in the
     * caller's history. The metadata go along — it works in the same place
     * for the same user.
     *
     * @throws IllegalArgumentException for a task without an agent or a
     *         message, and for an agent the caller may not call
     */
    TurnRequest request(Map<?, ?> task, Session session) {
        if (!(task.get(AGENT) instanceof String name) || name.isBlank()
                || !(task.get(MESSAGE) instanceof String message) || message.isBlank()) {
            throw new IllegalArgumentException("a task needs the agent's \"name\" and a \"message\"");
        }
        AgentDefinition agent = SubAgentPlugin.subAgentsOf(caller).contains(name)
                ? agents.resolve(name, session).orElse(null) : null;
        if (agent == null) {
            throw new IllegalArgumentException("there is no agent named " + name + " you can call. Available to you: "
                    + String.join(", ", SubAgentPlugin.subAgentsOf(caller)));
        }
        Session child = sessions.get().open(new SessionSpec(session.userId(), session.id(), agent, session.metadata()));
        return new TurnRequest(child.id(), List.of(new ContentPart.Text(message)), agent, Map.of());
    }
}
