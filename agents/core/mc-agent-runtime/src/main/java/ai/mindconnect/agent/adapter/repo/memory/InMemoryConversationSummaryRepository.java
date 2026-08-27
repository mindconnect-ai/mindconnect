package ai.mindconnect.agent.adapter.repo.memory;

import ai.mindconnect.agent.memory.domain.ConversationSummary;
import ai.mindconnect.agent.memory.port.out.ConversationSummaryRepository;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-memory {@link ConversationSummaryRepository} — process-lifetime storage, no persistence. */
public class InMemoryConversationSummaryRepository implements ConversationSummaryRepository {

    private final Map<UUID, List<ConversationSummary>> store = new ConcurrentHashMap<>();

    @Override
    public void save(ConversationSummary summary) {
        store.computeIfAbsent(summary.conversationId(), k -> new CopyOnWriteArrayList<>()).add(summary);
    }

    @Override
    public List<ConversationSummary> findByConversationId(UUID conversationId) {
        return List.copyOf(store.getOrDefault(conversationId, List.of()));
    }

    @Override
    public void deleteByConversationId(UUID conversationId) {
        store.remove(conversationId);
    }
}
