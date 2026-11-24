package ai.mindconnect.agent.runtime.api.domain;

import ai.mindconnect.agent.SessionId;

import java.util.Map;

/**
 * One call the model asked for, as data: which tool, with which arguments,
 * in which turn of which session. It travels — into a task's payload, back
 * to a caller inside an approval request — so it names the session and
 * carries none.
 */
public record ToolCall(
        String callId,
        String toolName,
        Map<String, Object> arguments,
        TurnId turnId,
        SessionId sessionId
) { }
