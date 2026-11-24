package ai.mindconnect.message.adapter.memory;

import ai.mindconnect.agent.scope.NamespaceMap;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.message.domain.ConversationId;

import ai.mindconnect.common.PageRequest;
import ai.mindconnect.message.domain.Conversation;
import ai.mindconnect.message.port.out.ConversationRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory {@link ConversationRepository} — process-lifetime storage, no persistence. */
public class InMemoryConversationRepository implements ConversationRepository {

    private final ScopeSupplier scope;
    private final NamespaceMap<Map<ConversationId, Conversation>> store;

    public InMemoryConversationRepository(ScopeSupplier scope) {
        this.scope = scope;
        this.store = new NamespaceMap<>(ConcurrentHashMap::new);
    }

    @Override
    public Conversation save(Conversation conversation) {
        store.of(scope.namespace()).put(conversation.id(), conversation);
        return conversation;
    }

    @Override
    public Optional<Conversation> findById(ConversationId id) {
        return Optional.ofNullable(store.of(scope.namespace()).get(id));
    }

    @Override
    public List<Conversation> findAll(PageRequest page) {
        return store.of(scope.namespace()).values().stream()
                .skip((long) page.page() * page.size())
                .limit(page.size())
                .toList();
    }

    @Override
    public void deleteById(ConversationId id) {
        store.of(scope.namespace()).remove(id);
    }
}
