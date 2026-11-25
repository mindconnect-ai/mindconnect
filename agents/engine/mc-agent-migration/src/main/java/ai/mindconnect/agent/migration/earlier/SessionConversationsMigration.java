package ai.mindconnect.agent.migration.earlier;

import java.time.Clock;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import ai.mindconnect.agent.migration.Migration;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.StoredMessage;
import ai.mindconnect.agent.runtime.store.SessionMessageRepository;
import ai.mindconnect.agent.runtime.store.SessionRepository;

/**
 * Moves the messages of the runtime's sessions that were kept as
 * conversations of the message repository, as the runtime did before
 * sessions had a repository of their own, each with its id, place and time.
 *
 * <p>A session's conversation is the one its metadata names, else the one
 * under its own id. A sub-agent's session was opened with its caller's
 * metadata; one that names its caller's conversation kept its messages under
 * its own id.
 */
public final class SessionConversationsMigration implements Migration {

    /** The key in a session's metadata that named the conversation its messages were in. */
    static final String CONVERSATION_ID = "conversationId";

    private final EarlierRuntimeData earlier;
    private final SessionRepository sessions;
    private final SessionMessageRepository target;
    private final EarlierMessages messages;
    private final Clock clock;

    /** @param json reads the content of a stored tool call or result */
    public SessionConversationsMigration(EarlierRuntimeData earlier, SessionRepository sessions,
                                         SessionMessageRepository target, ObjectMapper json, Clock clock) {
        this.earlier = earlier;
        this.sessions = sessions;
        this.target = target;
        this.messages = new EarlierMessages(json);
        this.clock = clock;
    }

    @Override
    public String id() {
        return "session-conversations";
    }

    @Override
    public String version() {
        return "0.8.5";
    }

    @Override
    public String migrate() {
        int moved = 0;
        int before = 0;
        for (Session session : sessions.findAll()) {
            if (session.metadata().containsKey(EarlierRuntimeMigration.MIGRATED_AT)
                    || !target.findBySession(session.id()).isEmpty()) {
                before++;
                continue;
            }
            List<StoredMessage> history = messages.read(earlier.messages(conversationOf(session)));
            target.restore(session.id(), history);
            sessions.update(session.id(),
                    s -> s.withMetadata(EarlierRuntimeMigration.MIGRATED_AT, clock.instant().toString()));
            moved++;
        }
        return "sessions: " + moved + " moved, " + before + " moved before";
    }

    private String conversationOf(Session session) {
        if (!(session.metadata().get(CONVERSATION_ID) instanceof String named)) {
            return session.id().value();
        }
        boolean inherited = session.parentId() != null && sessions.find(session.parentId())
                .map(parent -> conversationOf(parent).equals(named))
                .orElse(false);
        return inherited ? session.id().value() : named;
    }
}
