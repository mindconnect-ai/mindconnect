package ai.mindconnect.agent.runtime.store;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.StoredMessage;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;

/**
 * Moves the messages of a namespace's sessions from the store that kept them
 * as conversations of the message repository into their own repository, each
 * with its id, place and time. It runs again without harm: a session moved
 * before is left as it is.
 *
 * <p>The conversations stay where they are: switching back to
 * {@link SessionMessages#CONVERSATION} finds them as they were before the move.
 */
public final class SessionMessageMigration {

    /** Kept with a moved session: when its messages were moved. */
    public static final String MIGRATED_AT = "messagesMigratedAt";

    /** The sessions of one run: those moved, and those that had been moved before. */
    public record Report(List<SessionId> migrated, List<SessionId> skipped) { }

    private final SessionRepository sessions;
    private final SessionStore legacy;
    private final SessionMessageRepository target;
    private final Clock clock;

    /** @param legacy the store that reads the messages as conversations, on the same sessions */
    public SessionMessageMigration(SessionRepository sessions, SessionStore legacy, SessionMessageRepository target,
                                   Clock clock) {
        this.sessions = sessions;
        this.legacy = legacy;
        this.target = target;
        this.clock = clock;
    }

    /** Moves the sessions of the namespace the current call works in. */
    public Report run() {
        List<SessionId> migrated = new ArrayList<>();
        List<SessionId> skipped = new ArrayList<>();
        for (Session session : sessions.findAll()) {
            // Moved before; a session whose stamp is missing after a crash has its messages there all the same.
            if (session.metadata().containsKey(MIGRATED_AT) || !target.findBySession(session.id()).isEmpty()) {
                skipped.add(session.id());
                continue;
            }
            List<StoredMessage> history = legacy.history(session.id());
            target.restore(session.id(), history);
            sessions.update(session.id(), s -> s.withMetadata(MIGRATED_AT, clock.instant().toString()));
            migrated.add(session.id());
        }
        return new Report(List.copyOf(migrated), List.copyOf(skipped));
    }
}
