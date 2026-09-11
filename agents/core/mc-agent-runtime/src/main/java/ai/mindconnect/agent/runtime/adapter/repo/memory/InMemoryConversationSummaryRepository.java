package ai.mindconnect.agent.runtime.adapter.repo.memory;

import ai.mindconnect.message.domain.ConversationId;

import ai.mindconnect.agent.runtime.memory.domain.ConversationSummary;
import ai.mindconnect.agent.runtime.memory.port.out.ConversationSummaryRepository;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-memory {@link ConversationSummaryRepository} — process-lifetime storage, no persistence. */
public class InMemoryConversationSummaryRepository implements ConversationSummaryRepository {

    private final Map<ConversationId, List<ConversationSummary>> store = new ConcurrentHashMap<>();

    @Override
    public void save(ConversationSummary summary) {
        store.computeIfAbsent(summary.conversationId(), k -> new CopyOnWriteArrayList<>()).add(summary);
    }

    @Override
    public List<ConversationSummary> findByConversation(ConversationId conversationId) {
        return List.copyOf(store.getOrDefault(conversationId, List.of()));
    }

    @Override
    public void deleteByConversation(ConversationId conversationId) {
        store.remove(conversationId);
    }
}
