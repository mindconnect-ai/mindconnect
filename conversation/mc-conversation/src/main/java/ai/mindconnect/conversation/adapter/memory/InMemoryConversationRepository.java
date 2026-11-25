package ai.mindconnect.conversation.adapter.memory;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import ai.mindconnect.agent.scope.NamespaceMap;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.conversation.domain.Conversation;
import ai.mindconnect.conversation.domain.ConversationId;
import ai.mindconnect.conversation.port.out.ConversationRepository;

/** Conversations that last as long as the process: for tests and an installation that keeps nothing. */
public final class InMemoryConversationRepository implements ConversationRepository {

    private final ScopeSupplier scope;
    private final NamespaceMap<Map<ConversationId, Conversation>> store = new NamespaceMap<>(ConcurrentHashMap::new);

    public InMemoryConversationRepository(ScopeSupplier scope) {
        this.scope = scope;
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
                .sorted(Comparator.comparing(Conversation::createdAt).reversed())
                .skip(page.offset())
                .limit(page.size())
                .toList();
    }

    @Override
    public void deleteById(ConversationId id) {
        store.of(scope.namespace()).remove(id);
    }
}
