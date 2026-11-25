package ai.mindconnect.agent.runtime.store;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.runtime.api.domain.StoredMessage;
import ai.mindconnect.agent.scope.ScopeSupplier;

/** Sessions' messages that live as long as the process: for a library user who keeps nothing, and for tests. */
public final class InMemorySessionMessageRepository implements SessionMessageRepository {

    /** A session id means something only within its namespace. */
    private record Key(String namespace, SessionId sessionId) { }

    /** A session's messages and the last place given, which a removal does not take back. */
    private static final class Messages {
        private final List<StoredMessage> stored = new ArrayList<>();
        private long lastPlace;
    }

    private final Map<Key, Messages> sessions = new ConcurrentHashMap<>();
    private final ScopeSupplier scope;
    private final Clock clock;

    public InMemorySessionMessageRepository(ScopeSupplier scope, Clock clock) {
        this.scope = scope;
        this.clock = clock;
    }

    private Key key(SessionId session) {
        return new Key(scope.namespace().value(), session);
    }

    @Override
    public List<StoredMessage> findBySession(SessionId session) {
        Messages messages = sessions.get(key(session));
        if (messages == null) {
            return List.of();
        }
        synchronized (messages) {
            return List.copyOf(messages.stored);
        }
    }

    @Override
    public List<StoredMessage> append(SessionId session, List<TurnMessage> added) {
        Messages messages = sessions.computeIfAbsent(key(session), key -> new Messages());
        synchronized (messages) {
            List<StoredMessage> stored = new ArrayList<>();
            for (TurnMessage message : added) {
                TurnMessage identified = message.id() == null ? message.stored(UUID.randomUUID().toString()) : message;
                stored.add(new StoredMessage(identified, ++messages.lastPlace, clock.instant()));
            }
            messages.stored.addAll(stored);
            return stored;
        }
    }

    @Override
    public void restore(SessionId session, List<StoredMessage> restored) {
        Messages messages = sessions.computeIfAbsent(key(session), key -> new Messages());
        synchronized (messages) {
            for (StoredMessage message : restored) {
                messages.stored.add(message);
                messages.lastPlace = Math.max(messages.lastPlace, message.sequence());
            }
            messages.stored.sort(java.util.Comparator.comparingLong(StoredMessage::sequence));
        }
    }

    @Override
    public Optional<StoredMessage> update(SessionId session, String messageId, UnaryOperator<TurnMessage> change) {
        Messages messages = sessions.get(key(session));
        if (messages == null) {
            return Optional.empty();
        }
        synchronized (messages) {
            for (int i = 0; i < messages.stored.size(); i++) {
                StoredMessage stored = messages.stored.get(i);
                if (messageId.equals(stored.message().id())) {
                    StoredMessage changed = new StoredMessage(change.apply(stored.message()), stored.sequence(),
                            stored.storedAt());
                    messages.stored.set(i, changed);
                    return Optional.of(changed);
                }
            }
            return Optional.empty();
        }
    }

    @Override
    public int deleteRange(SessionId session, long from, long to) {
        Messages messages = sessions.get(key(session));
        if (messages == null) {
            return 0;
        }
        synchronized (messages) {
            int before = messages.stored.size();
            messages.stored.removeIf(stored -> stored.sequence() >= from && stored.sequence() <= to);
            return before - messages.stored.size();
        }
    }

    @Override
    public void deleteBySession(SessionId session) {
        sessions.remove(key(session));
    }
}
