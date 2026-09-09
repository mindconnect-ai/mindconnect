package ai.mindconnect.message.adapter.memory;

import ai.mindconnect.common.PageRequest;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.port.out.MessageRepository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntFunction;

/** In-memory {@link MessageRepository} — process-lifetime storage, no persistence. */
public class InMemoryMessageRepository implements MessageRepository {

    private final List<Message> store = new CopyOnWriteArrayList<>();

    /**
     * Upsert, not append: {@code save} is also how an existing message is
     * updated (compressed, token count, duration). Adding blindly would leave
     * two rows with the same id and a history that shows the message twice.
     */
    @Override
    public Message save(Message message) {
        store.removeIf(existing -> existing.id().equals(message.id()));
        store.add(message);
        return message;
    }

    @Override
    public List<Message> findByConversationId(UUID conversationId, PageRequest page) {
        return store.stream()
                .filter(m -> m.conversationId().equals(conversationId))
                .sorted(Comparator.comparingInt(Message::sequenceNum))
                .skip((long) page.page() * page.size())
                .limit(page.size())
                .toList();
    }

    @Override
    public Optional<Message> findById(UUID conversationId, UUID messageId) {
        return store.stream()
                .filter(m -> m.conversationId().equals(conversationId) && m.id().equals(messageId))
                .findFirst();
    }

    /**
     * One appender at a time, so two of them cannot read the same highest
     * number. The list itself is thread-safe; handing out a number is not,
     * and that is what needs guarding.
     */
    @Override
    public synchronized Message append(UUID conversationId, IntFunction<Message> create) {
        int next = store.stream()
                .filter(m -> m.conversationId().equals(conversationId))
                .mapToInt(Message::sequenceNum)
                .max().orElse(0) + 1;
        return save(create.apply(next));
    }

    @Override
    public void deleteBySequenceRange(UUID conversationId, int fromSeq, int toSeq) {
        store.removeIf(m -> m.conversationId().equals(conversationId)
                && m.sequenceNum() >= fromSeq && m.sequenceNum() <= toSeq);
    }
}
