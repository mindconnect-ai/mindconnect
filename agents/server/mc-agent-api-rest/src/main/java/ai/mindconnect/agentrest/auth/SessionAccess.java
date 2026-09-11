package ai.mindconnect.agentrest.auth;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

/**
 * The one question every session-addressed REST endpoint asks before it does
 * anything: is this the caller's session? A session id is not a secret — it
 * travels in URLs, logs and client state — so the id alone must not open a
 * session. A session that belongs to someone else is reported exactly like
 * one that does not exist.
 */
@Component
public class SessionAccess {

    private final AgentSessionRepository sessions;

    public SessionAccess(AgentSessionRepository sessions) {
        this.sessions = sessions;
    }

    /** The session, if it exists and belongs to {@code caller}. */
    public Optional<AgentSession> owned(SessionId sessionId, UserId caller) {
        return sessions.findById(sessionId).filter(session -> caller.equals(session.userId()));
    }

    /** The session, or a 404 for the request when it does not exist or is not the caller's. */
    public AgentSession requireOwned(SessionId sessionId, UserId caller) {
        return owned(sessionId, caller)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such session"));
    }
}
