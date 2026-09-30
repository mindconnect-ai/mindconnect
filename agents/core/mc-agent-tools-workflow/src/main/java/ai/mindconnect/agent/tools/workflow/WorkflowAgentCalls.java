package ai.mindconnect.agent.tools.workflow;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.domain.ToolApproval;
import ai.mindconnect.agent.runtime.domain.TurnResult;
import ai.mindconnect.agent.runtime.port.in.ChatTurnHandle;
import ai.mindconnect.agent.tool.ToolCallScope;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * One agent-call step: a chat turn with the named agent, and its answer.
 *
 * <p><b>Who asks.</b> The turn runs as the user the workflow runs for — the
 * {@link ToolCallScope} bound to the thread (an agent calling the workflow as
 * a tool, the scheduler running a job, a screen starting it) — so the agent
 * reads that user's mail and uses that user's connections. Only a run on
 * nobody's behalf falls back to the {@value #WORKFLOW_USER} user. The session
 * has the type {@value #SESSION_TYPE}: it belongs to the run, not to the
 * user's chat history.
 *
 * <p><b>How long.</b> A step waits for the turn's first outcome, at most the
 * configured timeout. A turn that stops for an approval cannot be answered
 * from inside a workflow, so it fails the step at once, naming the tools that
 * asked — before, it blocked the run for good.
 */
final class WorkflowAgentCalls {

    /** The user an agent call runs as when the workflow runs on nobody's behalf. */
    static final String WORKFLOW_USER = "workflow";
    /** The type of an agent-call step's session — kept out of the chat's history. */
    static final String SESSION_TYPE = "workflow";

    private final Function<String, Optional<AgentId>> agents;
    private final BiFunction<AgentId, UserId, SessionId> open;
    private final BiFunction<SessionId, String, ChatTurnHandle> submit;
    private final Duration timeout;

    /**
     * @param agents  an agent's id by its name
     * @param open    opens a session of {@link #SESSION_TYPE} with the agent for the user
     * @param submit  sends the message into the session
     * @param timeout how long a step waits for the turn
     */
    WorkflowAgentCalls(Function<String, Optional<AgentId>> agents, BiFunction<AgentId, UserId, SessionId> open,
                       BiFunction<SessionId, String, ChatTurnHandle> submit, Duration timeout) {
        this.agents = Objects.requireNonNull(agents, "agents");
        this.open = Objects.requireNonNull(open, "open");
        this.submit = Objects.requireNonNull(submit, "submit");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    /** The user of the scope bound to this thread, or {@value #WORKFLOW_USER}. */
    static UserId caller() {
        return ToolCallScope.current().map(ToolCallScope::userId).filter(Objects::nonNull)
                .orElseGet(() -> UserId.of(WORKFLOW_USER));
    }

    String call(String agentName, String message) {
        AgentId agent = agents.apply(agentName)
                .orElseThrow(() -> new IllegalArgumentException("No agent named '" + agentName + "'"));
        SessionId session = open.apply(agent, caller());
        ChatTurnHandle handle = submit.apply(session, message);
        TurnResult result;
        try {
            result = handle.outcome().get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            handle.cancel();
            throw new RuntimeException("Agent call to '" + agentName + "' gave no answer within "
                    + timeout.toMinutes() + " min", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handle.cancel();
            throw new RuntimeException("Agent call to '" + agentName + "' was interrupted", e);
        } catch (ExecutionException | CancellationException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new RuntimeException("Agent call to '" + agentName + "' failed: " + cause.getMessage(), cause);
        }
        if (result.isIncomplete()) {
            handle.cancel();
            String tools = result.pendingApprovals().stream().map(ToolApproval::toolName).distinct()
                    .reduce((a, b) -> a + ", " + b).orElse("a tool");
            throw new RuntimeException("Agent '" + agentName + "' stopped to ask for approval of " + tools
                    + ", which a workflow step cannot give. Let the agent run " + tools
                    + " without approval, or call it from a chat instead.");
        }
        return result.text();
    }
}
