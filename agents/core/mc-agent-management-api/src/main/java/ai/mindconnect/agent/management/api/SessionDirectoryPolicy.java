package ai.mindconnect.agent.management.api;

import java.util.Optional;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;

/**
 * What an installation says about the directories of its sessions: which
 * ones a user may name, and which one a session was given. The session
 * management asks it when a session is opened and when its directories
 * change; a server answers from its configuration, a tool on the user's own machine leaves it
 * {@link #unrestricted()}.
 */
public interface SessionDirectoryPolicy {

    /**
     * A directory the session does not have yet, as it is to be stored.
     *
     * @throws IllegalArgumentException saying why this user may not have it
     */
    String admit(UserId user, String directory);

    /** The directory the session was given and always keeps — its uploads live there; empty if it has none. */
    Optional<String> ownDirectory(ManagedSession session);

    /**
     * The directory a new session works in when none is named for it, made
     * if need be; empty where a session is given none.
     */
    Optional<String> assign(UserId user, SessionId session);

    /** Every directory as it is named, and no session with one of its own. */
    static SessionDirectoryPolicy unrestricted() {
        return new SessionDirectoryPolicy() {
            @Override
            public String admit(UserId user, String directory) {
                return directory;
            }

            @Override
            public Optional<String> ownDirectory(ManagedSession session) {
                return Optional.empty();
            }

            @Override
            public Optional<String> assign(UserId user, SessionId session) {
                return Optional.empty();
            }
        };
    }
}
