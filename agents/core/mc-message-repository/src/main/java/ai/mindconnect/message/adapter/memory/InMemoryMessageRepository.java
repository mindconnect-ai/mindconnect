package ai.mindconnect.message.adapter.memory;

import ai.mindconnect.agent.PerNamespace;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.message.domain.ConversationId;
import ai.mindconnect.message.domain.MessageId;

import ai.mindconnect.common.PageRequest;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.port.out.MessageRepository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntFunction;

/** In-memory {@link MessageRepository} — process-lifetime storage, no persistence. */
public class InMemoryMessageRepository implements MessageRepository {

    private final PerNamespace<List<Message>> store;

    public InMemoryMessageRepository(ScopeSupplier scope) {
        this.store = new PerNamespace<>(scope, CopyOnWriteArrayList::new);
    }

    /**
     * Upsert, not append: {@code save} is also how an existing message is
     * updated (compressed, token count, duration). Adding blindly would leave
     * two rows with the same id and a history that shows the message twice.
     */
    @Override
    public Message save(Message message) {
        store.current().removeIf(existing -> existing.id().equals(message.id()));
        store.current().add(message);
        return message;
    }

    /** Serialized with {@link #append}, so a change is applied to the message as it is stored. */
    @Override
    public synchronized Optional<Message> update(ConversationId conversation, MessageId id,
                                                 java.util.function.UnaryOperator<Message> change) {
        Optional<Message> current = findById(conversation, id);
        if (current.isEmpty()) return Optional.empty();
        Message changed = java.util.Objects.requireNonNull(change.apply(current.get()),
                "update: the change returned null");
        if (changed != current.get()) save(changed);
        return Optional.of(changed);
    }

    @Override
    public List<Message> findByConversation(ConversationId conversationId, PageRequest page) {
        return store.current().stream()
                .filter(m -> m.conversationId().equals(conversationId))
                .sorted(Comparator.comparingInt(Message::sequenceNum))
                .skip((long) page.page() * page.size())
                .limit(page.size())
                .toList();
    }

    @Override
    public Optional<Message> findById(ConversationId conversation, MessageId id) {
        return store.current().stream().filter(m -> m.conversationId().equals(conversation) && m.id().equals(id)).findFirst();
    }

    /**
     * One appender at a time, so two of them cannot read the same highest
     * number. The list itself is thread-safe; handing out a number is not,
     * and that is what needs guarding.
     */
    @Override
    public synchronized Message append(ConversationId conversationId, IntFunction<Message> create) {
        int next = store.current().stream()
                .filter(m -> m.conversationId().equals(conversationId))
                .mapToInt(Message::sequenceNum)
                .max().orElse(0) + 1;
        return save(create.apply(next));
    }

    @Override
    public void deleteBySequenceRange(ConversationId conversationId, int fromSeq, int toSeq) {
        store.current().removeIf(m -> m.conversationId().equals(conversationId)
                && m.sequenceNum() >= fromSeq && m.sequenceNum() <= toSeq);
    }

    @Override
    public void deleteByConversation(ConversationId conversationId) {
        store.current().removeIf(m -> m.conversationId().equals(conversationId));
    }
}
