package ai.mindconnect.agent.runtime.adapter.repo.memory;

import ai.mindconnect.agent.PerNamespace;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.message.domain.ConversationId;

import ai.mindconnect.agent.runtime.memory.domain.ConversationSummary;
import ai.mindconnect.agent.runtime.memory.port.out.ConversationSummaryRepository;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-memory {@link ConversationSummaryRepository} — process-lifetime storage, no persistence. */
public class InMemoryConversationSummaryRepository implements ConversationSummaryRepository {

    private final PerNamespace<Map<ConversationId, List<ConversationSummary>>> store;

    public InMemoryConversationSummaryRepository(ScopeSupplier scope) {
        this.store = new PerNamespace<>(scope, ConcurrentHashMap::new);
    }

    @Override
    public void save(ConversationSummary summary) {
        store.current().computeIfAbsent(summary.conversationId(), k -> new CopyOnWriteArrayList<>()).add(summary);
    }

    @Override
    public List<ConversationSummary> findByConversation(ConversationId conversationId) {
        return List.copyOf(store.current().getOrDefault(conversationId, List.of()));
    }

    @Override
    public void deleteByConversation(ConversationId conversationId) {
        store.current().remove(conversationId);
    }
}
