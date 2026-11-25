package ai.mindconnect.agent.runtime.engine.adapter.pg;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

import com.fasterxml.jackson.core.type.TypeReference;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.loop.domain.TurnMessageConverter;
import ai.mindconnect.agent.runtime.api.domain.StoredMessage;
import ai.mindconnect.agent.runtime.store.SessionMessageRepository;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.jdbc.Row;
import ai.mindconnect.jdbc.Sql;

/**
 * {@link SessionMessageRepository} on Postgres: one row of
 * {@code mc_session_message} per message, keyed by its session and place. The
 * message is the document, in {@link TurnMessageConverter}'s JSON, the form the
 * REST history shows. Every statement matches the namespace of the current
 * call.
 */
public final class PgSessionMessageRepository implements SessionMessageRepository {

    private static final String MESSAGE_TABLE = """
            CREATE TABLE IF NOT EXISTS mc_session_message (
                namespace  TEXT        NOT NULL,
                session_id TEXT        NOT NULL,
                seq        BIGINT      NOT NULL,
                id         TEXT        NOT NULL,
                stored_at  TIMESTAMPTZ NOT NULL,
                doc        JSONB       NOT NULL,
                PRIMARY KEY (namespace, session_id, seq),
                UNIQUE (namespace, session_id, id)
            )""";

    /**
     * One row per session holding the last place given. A removed place is
     * not given again, which the highest stored place alone would not know.
     * The upsert below takes the row's lock, so two appends to one session
     * queue up on it and leave with different places.
     */
    private static final String PLACE_TABLE = """
            CREATE TABLE IF NOT EXISTS mc_session_message_seq (
                namespace  TEXT   NOT NULL,
                session_id TEXT   NOT NULL,
                last_seq   BIGINT NOT NULL,
                PRIMARY KEY (namespace, session_id)
            )""";

    /** Takes as many places as are given and answers the last of them. */
    private static final String TAKE_PLACES = """
            INSERT INTO mc_session_message_seq (namespace, session_id, last_seq) VALUES (?, ?, ?)
            ON CONFLICT (namespace, session_id)
            DO UPDATE SET last_seq = mc_session_message_seq.last_seq + EXCLUDED.last_seq
            RETURNING last_seq""";

    private static final TypeReference<Map<String, Object>> DOCUMENT = new TypeReference<>() { };

    private final Sql sql;
    private final ScopeSupplier scope;
    private final Clock clock;
    private final TurnMessageConverter converter = new TurnMessageConverter();

    public PgSessionMessageRepository(Sql sql, ScopeSupplier scope, Clock clock) {
        this.sql = sql;
        this.scope = scope;
        this.clock = clock;
    }

    /** Creates the tables where they are missing. */
    public PgSessionMessageRepository initSchema() {
        sql.execute(MESSAGE_TABLE);
        sql.execute(PLACE_TABLE);
        return this;
    }

    @Override
    public List<StoredMessage> findBySession(SessionId session) {
        return sql.query("SELECT seq, stored_at, doc FROM mc_session_message "
                        + "WHERE namespace = ? AND session_id = ? ORDER BY seq",
                this::stored, scope.namespace().value(), session.value());
    }

    /** The places and the rows are written in one transaction: all messages are stored, or none. */
    @Override
    public List<StoredMessage> append(SessionId session, List<TurnMessage> messages) {
        if (messages.isEmpty()) {
            return List.of();
        }
        String namespace = scope.namespace().value();
        // As precise as the column keeps it, so what is answered here is what is read later.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        return sql.inTransaction(tx -> {
            long place = tx.scalar(TAKE_PLACES, Long.class, namespace, session.value(), (long) messages.size())
                    - messages.size();
            List<StoredMessage> stored = new ArrayList<>();
            for (TurnMessage message : messages) {
                TurnMessage identified = message.id() == null ? message.stored(UUID.randomUUID().toString()) : message;
                StoredMessage entry = new StoredMessage(identified, ++place, now);
                tx.update("INSERT INTO mc_session_message (namespace, session_id, seq, id, stored_at, doc) "
                                + "VALUES (?, ?, ?, ?, ?, ?)",
                        namespace, session.value(), entry.sequence(), identified.id(), now,
                        tx.json().jsonb(converter.toJson(identified)));
                stored.add(entry);
            }
            return stored;
        });
    }

    /** In one transaction, the session's last place raised to the highest one restored. */
    @Override
    public void restore(SessionId session, List<StoredMessage> messages) {
        if (messages.isEmpty()) {
            return;
        }
        String namespace = scope.namespace().value();
        long highest = messages.stream().mapToLong(StoredMessage::sequence).max().orElseThrow();
        sql.inTransaction((Sql tx) -> {
            tx.update("""
                    INSERT INTO mc_session_message_seq (namespace, session_id, last_seq) VALUES (?, ?, ?)
                    ON CONFLICT (namespace, session_id)
                    DO UPDATE SET last_seq = greatest(mc_session_message_seq.last_seq, EXCLUDED.last_seq)""",
                    namespace, session.value(), highest);
            for (StoredMessage message : messages) {
                tx.update("INSERT INTO mc_session_message (namespace, session_id, seq, id, stored_at, doc) "
                                + "VALUES (?, ?, ?, ?, ?, ?)",
                        namespace, session.value(), message.sequence(), message.message().id(),
                        message.storedAt().truncatedTo(ChronoUnit.MICROS),
                        tx.json().jsonb(converter.toJson(message.message())));
            }
        });
    }

    /** Reads the row {@code FOR UPDATE} and writes the change in the same transaction. */
    @Override
    public Optional<StoredMessage> update(SessionId session, String messageId, UnaryOperator<TurnMessage> change) {
        String namespace = scope.namespace().value();
        return sql.inTransaction((Sql tx) -> {
            Optional<StoredMessage> current = tx.queryOne("SELECT seq, stored_at, doc FROM mc_session_message "
                            + "WHERE namespace = ? AND session_id = ? AND id = ? FOR UPDATE",
                    this::stored, namespace, session.value(), messageId);
            return current.map(found -> {
                StoredMessage changed = new StoredMessage(change.apply(found.message()), found.sequence(),
                        found.storedAt());
                tx.update("UPDATE mc_session_message SET doc = ? WHERE namespace = ? AND session_id = ? AND seq = ?",
                        tx.json().jsonb(converter.toJson(changed.message())), namespace, session.value(),
                        found.sequence());
                return changed;
            });
        });
    }

    @Override
    public int deleteRange(SessionId session, long from, long to) {
        return sql.update("DELETE FROM mc_session_message WHERE namespace = ? AND session_id = ? AND seq BETWEEN ? AND ?",
                scope.namespace().value(), session.value(), from, to);
    }

    /** The session's last place stays: a session of that id later on does not reuse its places. */
    @Override
    public void deleteBySession(SessionId session) {
        sql.update("DELETE FROM mc_session_message WHERE namespace = ? AND session_id = ?",
                scope.namespace().value(), session.value());
    }

    private StoredMessage stored(Row row) throws java.sql.SQLException {
        return new StoredMessage(converter.fromJson(row.json("doc", DOCUMENT)), row.longValue("seq"),
                row.instant("stored_at"));
    }
}
