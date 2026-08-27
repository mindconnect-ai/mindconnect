package ai.mindconnect.cli;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/**
 * Persisted "where the user was" between CLI runs.
 * Written at three points: when the user enters the session menu, when a session
 * becomes active, and when the user returns to the agent list. Read once at startup
 * and used to jump straight back into the previous view.
 */
public record CliState(View view, UUID lastAgentId, UUID lastSessionId) {

    public enum View { AGENT_LIST, SESSION_LIST, ACTIVE_SESSION }

    @JsonCreator
    public static CliState fromJson(
            @JsonProperty("view") View view,
            @JsonProperty("lastAgentId") UUID lastAgentId,
            @JsonProperty("lastSessionId") UUID lastSessionId) {
        return new CliState(view == null ? View.AGENT_LIST : view, lastAgentId, lastSessionId);
    }

    public static CliState empty() {
        return new CliState(View.AGENT_LIST, null, null);
    }

    public static CliState agentList() {
        return new CliState(View.AGENT_LIST, null, null);
    }

    public static CliState sessionList(UUID agentId) {
        return new CliState(View.SESSION_LIST, agentId, null);
    }

    public static CliState activeSession(UUID agentId, UUID sessionId) {
        return new CliState(View.ACTIVE_SESSION, agentId, sessionId);
    }
}
