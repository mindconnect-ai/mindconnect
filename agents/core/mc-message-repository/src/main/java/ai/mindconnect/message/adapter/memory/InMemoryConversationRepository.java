package ai.mindconnect.message.adapter.memory;

import ai.mindconnect.common.Namespace;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.message.domain.Conversation;
import ai.mindconnect.message.port.out.ConversationRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory {@link ConversationRepository} — process-lifetime storage, no persistence. */
public class InMemoryConversationRepository implements ConversationRepository {

    private final Map<UUID, Conversation> store = new ConcurrentHashMap<>();

    @Override
    public Conversation save(Conversation conversation) {
        store.put(conversation.id(), conversation);
        return conversation;
    }

    @Override
    public Optional<Conversation> findById(UUID id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<Conversation> findByNamespace(Namespace namespace, PageRequest page) {
        return store.values().stream()
                .filter(c -> c.namespace().equals(namespace))
                .skip((long) page.page() * page.size())
                .limit(page.size())
                .toList();
    }
}
