package ai.mindconnect.conversation.adapter.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongFunction;
import java.util.function.UnaryOperator;

import ai.mindconnect.agent.scope.NamespaceMap;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.conversation.domain.ConversationId;
import ai.mindconnect.conversation.domain.Message;
import ai.mindconnect.conversation.domain.MessageId;
import ai.mindconnect.conversation.port.out.MessageRepository;

/** Messages that last as long as the process: for tests and an installation that keeps nothing. */
public final class InMemoryMessageRepository implements MessageRepository {

    /** A conversation's messages and the last place given. */
    private static final class Thread {
        private final List<Message> messages = new ArrayList<>();
        private long lastPlace;
    }

    private final ScopeSupplier scope;
    private final NamespaceMap<Map<ConversationId, Thread>> store = new NamespaceMap<>(ConcurrentHashMap::new);

    public InMemoryMessageRepository(ScopeSupplier scope) {
        this.scope = scope;
    }

    private Optional<Thread> thread(ConversationId conversation) {
        return Optional.ofNullable(store.of(scope.namespace()).get(conversation));
    }

    @Override
    public Message append(ConversationId conversation, LongFunction<Message> create) {
        Thread thread = store.of(scope.namespace()).computeIfAbsent(conversation, id -> new Thread());
        synchronized (thread) {
            Message message = Objects.requireNonNull(create.apply(++thread.lastPlace), "append: no message");
            thread.messages.add(message);
            return message;
        }
    }

    @Override
    public Optional<Message> update(ConversationId conversation, MessageId id, UnaryOperator<Message> change) {
        return thread(conversation).flatMap(thread -> {
            synchronized (thread) {
                for (int i = 0; i < thread.messages.size(); i++) {
                    if (thread.messages.get(i).id().equals(id)) {
                        Message changed = Objects.requireNonNull(change.apply(thread.messages.get(i)),
                                "update: the change returned null");
                        thread.messages.set(i, changed);
                        return Optional.of(changed);
                    }
                }
                return Optional.empty();
            }
        });
    }

    @Override
    public List<Message> findByConversation(ConversationId conversation, PageRequest page) {
        return thread(conversation).map(thread -> {
            synchronized (thread) {
                return thread.messages.stream().skip(page.offset()).limit(page.size()).toList();
            }
        }).orElse(List.of());
    }

    @Override
    public Optional<Message> findById(ConversationId conversation, MessageId id) {
        return findByConversation(conversation, new PageRequest(0, Integer.MAX_VALUE)).stream()
                .filter(message -> message.id().equals(id)).findFirst();
    }

    @Override
    public void deleteByConversation(ConversationId conversation) {
        store.of(scope.namespace()).remove(conversation);
    }
}
