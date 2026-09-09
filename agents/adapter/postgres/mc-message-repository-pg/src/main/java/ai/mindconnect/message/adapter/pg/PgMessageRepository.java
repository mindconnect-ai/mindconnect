package ai.mindconnect.message.adapter.pg;

import ai.mindconnect.common.PageRequest;
import ai.mindconnect.jdbc.DocumentTable;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.port.out.MessageRepository;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntFunction;

/**
 * {@link MessageRepository} on Postgres. One row of {@code mc_message} per
 * message, keyed by the message id so a re-save (the compressed flag, a
 * token count) replaces the row; the conversation id and the sequence
 * number sit beside the document because every read walks a conversation
 * in sequence order and the range delete cuts by it.
 *
 * <p>No foreign key to the conversation: the file store never required the
 * conversation to exist first, and callers rely on that.
 */
public final class PgMessageRepository implements MessageRepository {

    /**
     * One row per conversation holding the last number handed out. The
     * upsert below takes that row's lock, so two appenders queue up on it
     * and leave with different numbers; without it both would read the
     * same highest {@code seq} and write it twice. The row seeds itself
     * from the messages already stored, so a conversation that predates
     * this table carries on where it left off.
     */
    private static final String SEQUENCE_TABLE = """
            CREATE TABLE IF NOT EXISTS mc_message_seq (
                conversation_id UUID PRIMARY KEY,
                last_seq        INTEGER NOT NULL
            )""";

    private static final String NEXT_SEQUENCE = """
            INSERT INTO mc_message_seq (conversation_id, last_seq)
            VALUES (?, (SELECT coalesce(max(seq), 0) + 1 FROM mc_message WHERE conversation_id = ?))
            ON CONFLICT (conversation_id) DO UPDATE SET last_seq = mc_message_seq.last_seq + 1
            RETURNING last_seq""";

    private final DocumentTable<Message> messages;
    private final Sql sql;

    public PgMessageRepository(DataSource dataSource) {
        this(Sql.of(dataSource));
    }

    /** Share a {@link Sql} — and with it the application's JSON mapper — with the other stores. */
    public PgMessageRepository(Sql sql) {
        this.sql = sql;
        this.messages = DocumentTable.of(Message.class)
                .table("mc_message")
                .id("id", "UUID", Message::id)
                .requiredColumn("conversation_id", "UUID", Message::conversationId)
                .requiredColumn("seq", "INTEGER", Message::sequenceNum)
                .index("conversation_id", "seq")
                .build(sql);
    }

    /** Runs the idempotent DDL ({@code CREATE TABLE IF NOT EXISTS …}). */
    public PgMessageRepository initSchema() {
        messages.createSchema();
        sql.execute(SEQUENCE_TABLE);
        return this;
    }

    @Override
    public Message save(Message message) {
        return messages.save(message);
    }

    @Override
    public List<Message> findByConversationId(UUID conversationId, PageRequest page) {
        return messages.find("WHERE conversation_id = ? ORDER BY seq, id LIMIT ? OFFSET ?",
                conversationId, page.size(), page.offset());
    }

    @Override
    public Optional<Message> findById(UUID conversationId, UUID messageId) {
        return messages.findOne("WHERE id = ? AND conversation_id = ?", messageId, conversationId);
    }

    /**
     * The number comes from the counter row, in one statement, before the
     * message is written. Nothing is retried and nothing is locked in
     * Java: the row lock the upsert takes is what serialises two appenders.
     */
    @Override
    public Message append(UUID conversationId, IntFunction<Message> create) {
        int next = sql.scalar(NEXT_SEQUENCE, Integer.class, conversationId, conversationId);
        return messages.save(create.apply(next));
    }

    @Override
    public void deleteBySequenceRange(UUID conversationId, int fromSeq, int toSeq) {
        messages.delete("WHERE conversation_id = ? AND seq BETWEEN ? AND ?", conversationId, fromSeq, toSeq);
    }
}
