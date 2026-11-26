package ai.mindconnect.conversation.adapter.memory;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import ai.mindconnect.scope.TenantMap;
import ai.mindconnect.scope.TenantScope;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.conversation.domain.Conversation;
import ai.mindconnect.conversation.domain.ConversationId;
import ai.mindconnect.conversation.port.out.ConversationRepository;

/** Conversations that last as long as the process: for tests and an installation that keeps nothing. */
public final class InMemoryConversationRepository implements ConversationRepository {

    private final TenantScope scope;
    private final TenantMap<Map<ConversationId, Conversation>> store = new TenantMap<>(ConcurrentHashMap::new);

    public InMemoryConversationRepository(TenantScope scope) {
        this.scope = scope;
    }

    @Override
    public Conversation save(Conversation conversation) {
        store.of(scope).put(conversation.id(), conversation);
        return conversation;
    }

    @Override
    public Optional<Conversation> findById(ConversationId id) {
        return Optional.ofNullable(store.of(scope).get(id));
    }

    @Override
    public List<Conversation> findAll(PageRequest page) {
        return store.of(scope).values().stream()
                .sorted(Comparator.comparing(Conversation::createdAt).reversed())
                .skip(page.offset())
                .limit(page.size())
                .toList();
    }

    @Override
    public void deleteById(ConversationId id) {
        store.of(scope).remove(id);
    }
}
