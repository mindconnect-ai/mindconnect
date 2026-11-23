package ai.mindconnect.agent.runtime.capabilities.memory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import ai.mindconnect.agent.runtime.api.domain.SessionId;
import ai.mindconnect.agent.runtime.api.port.out.NamespaceProvider;

/** Summaries that live as long as the process; a session summarised before a restart is summarised again. */
public final class InMemorySummaryStore implements SummaryStore {

    private final Map<String, ConversationSummary> summaries = new ConcurrentHashMap<>();
    private final NamespaceProvider namespaces;

    public InMemorySummaryStore(NamespaceProvider namespaces) {
        this.namespaces = namespaces;
    }

    /** A session id means something only within its namespace. */
    private String key(SessionId sessionId) {
        return namespaces.current() + "/" + sessionId.value();
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
