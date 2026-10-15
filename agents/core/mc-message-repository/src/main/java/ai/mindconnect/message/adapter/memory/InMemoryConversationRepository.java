package ai.mindconnect.message.adapter.memory;

import ai.mindconnect.agent.PerNamespace;
import ai.mindconnect.agent.ScopeSupplier;
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

    private final PerNamespace<Map<ConversationId, Conversation>> store;

    public InMemoryConversationRepository(ScopeSupplier scope) {
        this.store = new PerNamespace<>(scope, ConcurrentHashMap::new);
    }

    @Override
    public Conversation save(Conversation conversation) {
        store.current().put(conversation.id(), conversation);
        return conversation;
    }

    @Override
    public Optional<Conversation> findById(ConversationId id) {
        return Optional.ofNullable(store.current().get(id));
    }

    @Override
    public List<Conversation> findAll(PageRequest page) {
        return store.current().values().stream()
                .skip((long) page.page() * page.size())
                .limit(page.size())
                .toList();
    }

    @Override
    public void deleteById(ConversationId id) {
        store.current().remove(id);
    }
}
