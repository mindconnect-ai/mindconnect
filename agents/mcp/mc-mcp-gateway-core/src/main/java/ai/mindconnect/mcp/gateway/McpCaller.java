package ai.mindconnect.mcp.gateway;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;

/**
 * Who is calling, on whose behalf. Carried into every tool call so the
 * gateway can pick the right credentials, pool connections per session and
 * write a useful audit trail.
 *
 * <p>Deliberately not a security token: in the in-process gateway the
 * caller and the gateway are the same JVM, so identity is a fact rather
 * than a claim. A remote gateway needs a token proving both the calling
 * runtime and this user — that is the trust boundary described in concept
 * 21 §5.5, and it does not exist yet.
 *
 * @param userId     the end user the call is made for; null when nobody is signed in
 * @param sessionId  the agent session; the unit connections are pooled by
 */
public record McpCaller(UserId userId, SessionId sessionId) {

    public McpCaller {
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId required");
        }
    }
}
