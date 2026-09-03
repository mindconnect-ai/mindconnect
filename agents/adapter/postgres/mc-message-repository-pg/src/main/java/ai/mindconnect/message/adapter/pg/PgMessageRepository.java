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

    private final DocumentTable<Message> messages;

    public PgMessageRepository(DataSource dataSource) {
        this(Sql.of(dataSource));
    }

    /** Share a {@link Sql} — and with it the application's JSON mapper — with the other stores. */
    public PgMessageRepository(Sql sql) {
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

    @Override
    public int countByConversationId(UUID conversationId) {
        return messages.count("WHERE conversation_id = ?", conversationId);
    }

    @Override
    public void deleteBySequenceRange(UUID conversationId, int fromSeq, int toSeq) {
        messages.delete("WHERE conversation_id = ? AND seq BETWEEN ? AND ?", conversationId, fromSeq, toSeq);
    }
}
