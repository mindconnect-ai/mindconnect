package ai.mindconnect.agent.runtime.capabilities.memory;

import java.util.Optional;

import ai.mindconnect.agent.SessionId;

/**
 * Where the summary of a session is kept. One per session: a new one covers
 * what the one before covered and more, and takes its place.
 */
public interface SummaryStore {

    Optional<ConversationSummary> find(SessionId sessionId);

    void save(SessionId sessionId, ConversationSummary summary);
}
