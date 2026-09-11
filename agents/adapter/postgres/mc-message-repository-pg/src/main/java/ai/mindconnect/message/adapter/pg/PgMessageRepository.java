package ai.mindconnect.message.adapter.pg;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.jdbc.DocumentTable;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.message.domain.ConversationId;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageId;
import ai.mindconnect.message.port.out.MessageRepository;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntFunction;
import java.util.function.UnaryOperator;

/**
 * {@link MessageRepository} on Postgres. One row of {@code mc_message} per
 * message, keyed by {@code (namespace, id)} so a re-save (the compressed flag,
 * a token count) replaces the row; the conversation id and the sequence
 * number sit beside the document because every read walks a conversation
 * in sequence order and the range delete cuts by it. The repository is
 * bound to one namespace and every statement matches it.
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
     * from the messages already stored, so a conversation whose messages
     * were saved without {@link #append} carries on where it left off.
     */
    private static final String SEQUENCE_TABLE = """
            CREATE TABLE IF NOT EXISTS mc_message_seq (
                namespace       TEXT NOT NULL,
                conversation_id TEXT NOT NULL,
                last_seq        INTEGER NOT NULL,
                PRIMARY KEY (namespace, conversation_id)
            )""";

    private static final String NEXT_SEQUENCE = """
            INSERT INTO mc_message_seq (namespace, conversation_id, last_seq)
            VALUES (?, ?, (SELECT coalesce(max(seq), 0) + 1 FROM mc_message WHERE namespace = ? AND conversation_id = ?))
            ON CONFLICT (namespace, conversation_id) DO UPDATE SET last_seq = mc_message_seq.last_seq + 1
            RETURNING last_seq""";

    private final DocumentTable<Message> messages;
    private final Sql sql;
    private final Namespace namespace;

    public PgMessageRepository(DataSource dataSource, Namespace namespace) {
        this(Sql.of(dataSource), namespace);
    }

    /** Share a {@link Sql} — and with it the application's JSON mapper — with the other stores. */
    public PgMessageRepository(Sql sql, Namespace namespace) {
        this.sql = sql;
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.messages = DocumentTable.of(Message.class)
                .table("mc_message")
                .partitionKey("namespace", "TEXT", m -> namespace.value())
                .id("id", "TEXT", m -> m.id().value())
                .requiredColumn("conversation_id", "TEXT", m -> m.conversationId().value())
                .requiredColumn("seq", "INTEGER", Message::sequenceNum)
                .index("namespace", "conversation_id", "seq")
                .build(sql);
    }

    public PgMessageRepository initSchema() {
        messages.createSchema();
        sql.execute(SEQUENCE_TABLE);
        return this;
    }

    @Override
    public Message save(Message message) {
        return messages.save(message);
    }

    /** Reads the row {@code FOR UPDATE} and writes the change in the same transaction. */
    @Override
    public Optional<Message> update(ConversationId conversation, MessageId id, UnaryOperator<Message> change) {
        return messages.update(namespace.value(), id.value(), change);
    }

    @Override
    public List<Message> findByConversation(ConversationId conversation, PageRequest page) {
        return messages.find("WHERE namespace = ? AND conversation_id = ? ORDER BY seq, id LIMIT ? OFFSET ?",
                namespace.value(), conversation.value(), page.size(), page.offset());
    }

    @Override
    public Optional<Message> findById(ConversationId conversation, MessageId id) {
        return messages.findOne("WHERE namespace = ? AND conversation_id = ? AND id = ?",
                namespace.value(), conversation.value(), id.value());
    }

    /**
     * The sequence number is handed out by the database in the same statement
     * that records it, so two appends racing for the same conversation get two
     * numbers — the file store's lock, in SQL.
     */
    @Override
    public Message append(ConversationId conversation, IntFunction<Message> create) {
        int next = sql.scalar(NEXT_SEQUENCE, Integer.class, namespace.value(), conversation.value(),
                namespace.value(), conversation.value());
        return messages.save(create.apply(next));
    }

    @Override
    public void deleteBySequenceRange(ConversationId conversation, int fromSeq, int toSeq) {
        messages.delete("WHERE namespace = ? AND conversation_id = ? AND seq BETWEEN ? AND ?",
                namespace.value(), conversation.value(), fromSeq, toSeq);
    }
}
