package ai.mindconnect.agent.runtime.store;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

import com.fasterxml.jackson.databind.ObjectMapper;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.loop.domain.TurnMessageConverter;
import ai.mindconnect.agent.runtime.api.domain.StoredMessage;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.filerepo.FileRepo;
import ai.mindconnect.filerepo.RecordLog;

/**
 * Sessions' messages under {@code {base}/{namespace}/sessions/{sessionId}/messages/},
 * one file per message named by its place and id, beside the session's own
 * file. The record log keeps a removed place from being given again, also
 * after a restart, and writes one session's messages one at a time.
 *
 * <p>A message lies in its file in {@link TurnMessageConverter}'s JSON, the
 * form the REST history answers with: what is stored is what is shown.
 */
public final class FileSessionMessageRepository implements SessionMessageRepository {

    private static final String SESSIONS = "sessions";
    private static final String MESSAGES = "messages";

    /** One message as its file holds it. */
    record Entry(long sequence, Instant storedAt, Map<String, Object> message) { }

    private final RecordLog<SessionId, Entry> log;
    private final TurnMessageConverter converter = new TurnMessageConverter();
    private final Clock clock;

    public FileSessionMessageRepository(Path base, ObjectMapper json, ScopeSupplier scope, Clock clock) {
        this.clock = clock;
        this.log = RecordLog.of(Entry.class)
                .dir((SessionId id) -> SESSIONS + "/" + id.value() + "/" + MESSAGES)
                .key(Entry::sequence)
                .id(entry -> (String) entry.message().get("id"))
                // The partition of the namespace the current call works in.
                .build(() -> FileRepo.open(base, scope.namespace().value()), json);
    }

    @Override
    public List<StoredMessage> findBySession(SessionId session) {
        return log.all(session).stream().map(this::stored).toList();
    }

    /** Each message is its own write: a crash in between keeps those before it. */
    @Override
    public List<StoredMessage> append(SessionId session, List<TurnMessage> messages) {
        List<StoredMessage> stored = new ArrayList<>();
        for (TurnMessage message : messages) {
            TurnMessage identified = message.id() == null ? message.stored(UUID.randomUUID().toString()) : message;
            Entry entry = log.append(session,
                    place -> new Entry(place, clock.instant(), converter.toJson(identified)));
            stored.add(stored(entry));
        }
        return stored;
    }

    @Override
    public Optional<StoredMessage> update(SessionId session, String messageId, UnaryOperator<TurnMessage> change) {
        return log.update(session, messageId, entry -> new Entry(entry.sequence(), entry.storedAt(),
                        converter.toJson(change.apply(converter.fromJson(entry.message())))))
                .map(this::stored);
    }

    @Override
    public int deleteRange(SessionId session, long from, long to) {
        return log.deleteKeyRange(session, from, to);
    }

    @Override
    public void deleteBySession(SessionId session) {
        log.deleteAll(session);
    }

    private StoredMessage stored(Entry entry) {
        return new StoredMessage(converter.fromJson(entry.message()), entry.sequence(), entry.storedAt());
    }
}
