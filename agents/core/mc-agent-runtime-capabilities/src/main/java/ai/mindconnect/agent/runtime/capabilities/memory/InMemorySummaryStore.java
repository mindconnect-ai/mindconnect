package ai.mindconnect.agent.runtime.capabilities.memory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.scope.ScopeSupplier;

/** Summaries that live as long as the process; a session summarised before a restart is summarised again. */
public final class InMemorySummaryStore implements SummaryStore {

    private final Map<String, ConversationSummary> summaries = new ConcurrentHashMap<>();
    private final ScopeSupplier scope;

    public InMemorySummaryStore(ScopeSupplier scope) {
        this.scope = scope;
    }

    /** A session id means something only within its namespace. */
    private String key(SessionId sessionId) {
        return scope.namespace().value() + "/" + sessionId.value();
    }

    @Override
    public Optional<ConversationSummary> find(SessionId sessionId) {
        return Optional.ofNullable(summaries.get(key(sessionId)));
    }

    @Override
    public void save(SessionId sessionId, ConversationSummary summary) {
        summaries.put(key(sessionId), summary);
    }
}
