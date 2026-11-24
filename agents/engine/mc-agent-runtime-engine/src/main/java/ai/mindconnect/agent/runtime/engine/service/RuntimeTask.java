package ai.mindconnect.agent.runtime.engine.service;

import java.util.Map;
import java.util.Optional;

import ai.mindconnect.taskqueue.TaskRecord;

/**
 * What a monitor may read off a task the runtime queued, without knowing how
 * the engine lays out its payloads: what kind of work it is, for which
 * session, and in whose namespace it runs.
 *
 * @param namespace the namespace the task was submitted in
 * @param user      who acted when its turn was submitted; {@code null} where nobody was known
 * @param agentName the agent a turn runs; {@code null} for a tool call
 * @param toolName  the tool a tool call runs; {@code null} for a turn
 */
public record RuntimeTask(Kind kind, String namespace, String user, String sessionId, String agentName,
                          String toolName) {

    public enum Kind {
        TURN,
        TOOL_CALL
    }

    /** Empty for a task that is not the runtime's. */
    public static Optional<RuntimeTask> of(TaskRecord task) {
        Map<String, Object> payload = task.payload();
        String namespace = payload.get(CallerBoundWorker.NAMESPACE) instanceof String value ? value : null;
        String user = payload.get(CallerBoundWorker.USER) instanceof String value ? value : null;
        String sessionId = payload.get(ToolCallWorker.SESSION_ID) instanceof String value ? value : null;
        if (TurnTaskMapper.TYPE.equals(task.type())) {
            return Optional.of(new RuntimeTask(Kind.TURN, namespace, user, sessionId,
                    TurnTaskMapper.agentName(task), null));
        }
        if (ToolCallWorker.TYPE.equals(task.type())) {
            return Optional.of(new RuntimeTask(Kind.TOOL_CALL, namespace, user, sessionId, null,
                    payload.get(ToolCallWorker.TOOL_NAME) instanceof String value ? value : null));
        }
        return Optional.empty();
    }
}
