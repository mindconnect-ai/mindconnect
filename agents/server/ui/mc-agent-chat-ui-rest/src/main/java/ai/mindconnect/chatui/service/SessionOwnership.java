package ai.mindconnect.chatui.service;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The one question every session-addressed chat endpoint has to ask before
 * it does anything: is this the caller's chat? A session id is not a secret
 * — it travels in URLs, links, logs and the DOM — so the id alone must not
 * open a chat. The owner is the user who started it ({@link
 * AgentSession#userId()}), and a session that is not the caller's is
 * reported as not existing, which is all a stranger gets to learn.
 *
 * <p>Streams are addressed by channel, and the chat's channel is derived
 * from its session ({@code msg-list-<sessionId>}), so a channel is owned
 * exactly when the session it names is.
 */
@Component
public class SessionOwnership {

    /** The channel prefix the chat page uses for its message list stream. */
    public static final String CHANNEL_PREFIX = "msg-list-";

    /** The user every request runs as when no principal is present. */
    public static final String ANONYMOUS_USER = "mc_user";

    private final AgentSessionRepository sessions;

    public SessionOwnership(AgentSessionRepository sessions) {
        this.sessions = sessions;
    }

    /** The session, if it exists and belongs to the caller. */
    public Optional<AgentSession> owned(SessionId sessionId, OidcUser user) {
        return sessions.findById(sessionId).filter(session -> owns(session, user));
    }

    /** The session behind a chat stream channel, if it exists and belongs to the caller. */
    public Optional<AgentSession> ownedByChannel(String channelId, OidcUser user) {
        return sessionIdOf(channelId).flatMap(id -> owned(id, user));
    }

    /** Whether the caller is the user who started the session. */
    public static boolean owns(AgentSession session, OidcUser user) {
        String caller = userIdOf(user);
        return caller != null && session.userId() != null && caller.equals(session.userId().value());
    }

    /** The caller's user id: the OIDC preferred username, or the fixed dev user without a principal. */
    public static String userIdOf(OidcUser user) {
        return user == null ? ANONYMOUS_USER : user.getPreferredUsername();
    }

    /** The channel the chat page streams a session's turns on. */
    public static String channelOf(SessionId sessionId) {
        return CHANNEL_PREFIX + sessionId.value();
    }

    /** The session a chat channel belongs to; empty for any other channel shape. */
    public static Optional<SessionId> sessionIdOf(String channelId) {
        if (channelId == null || !channelId.startsWith(CHANNEL_PREFIX)) {
            return Optional.empty();
        }
        try {
            return Optional.of(SessionId.of(channelId.substring(CHANNEL_PREFIX.length())));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
