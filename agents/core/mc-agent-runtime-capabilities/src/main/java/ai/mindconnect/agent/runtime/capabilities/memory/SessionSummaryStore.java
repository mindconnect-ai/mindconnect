package ai.mindconnect.agent.runtime.capabilities.memory;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;

/**
 * Keeps a session's summary with the session itself, in its metadata: it
 * lasts as long as the session, goes with it, and needs no store of its own.
 */
public final class SessionSummaryStore implements SummaryStore {

    /** The key in a session's metadata that holds its summary. */
    public static final String SUMMARY = "summary";

    private static final String TEXT = "text";
    private static final String THROUGH = "throughMessageId";

    private final Supplier<SessionStore> sessions;

    public SessionSummaryStore(Supplier<SessionStore> sessions) {
        this.sessions = sessions;
    }

    @Override
    public Optional<ConversationSummary> find(SessionId sessionId) {
        return sessions.get().find(sessionId)
                .map(session -> session.metadata().get(SUMMARY))
                .filter(Map.class::isInstance)
                .map(stored -> (Map<?, ?>) stored)
                .filter(stored -> stored.get(TEXT) instanceof String && stored.get(THROUGH) instanceof String)
                .map(stored -> new ConversationSummary((String) stored.get(TEXT), (String) stored.get(THROUGH)));
    }

    @Override
    public void save(SessionId sessionId, ConversationSummary summary) {
        sessions.get().update(sessionId, session -> session.withMetadata(SUMMARY,
                Map.of(TEXT, summary.text(), THROUGH, summary.throughMessageId())));
    }
}
