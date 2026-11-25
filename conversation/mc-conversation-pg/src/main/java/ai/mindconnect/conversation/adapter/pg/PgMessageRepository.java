package ai.mindconnect.conversation.adapter.pg;

import java.util.List;
import java.util.Optional;
import java.util.function.LongFunction;
import java.util.function.UnaryOperator;

import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.conversation.domain.ConversationId;
import ai.mindconnect.conversation.domain.Message;
import ai.mindconnect.conversation.domain.MessageId;
import ai.mindconnect.conversation.port.out.MessageRepository;
import ai.mindconnect.jdbc.DocumentTable;
import ai.mindconnect.jdbc.Sql;

/**
 * {@link MessageRepository} on Postgres: one row of {@code mc_conversation_entry}
 * per message, keyed by {@code (namespace, id)}, its conversation and place
 * beside the document. Not {@code mc_message}: there the runtime kept its
 * sessions' messages before, and those rows stay as they were written.
 */
public final class PgMessageRepository implements MessageRepository {

    /**
     * One row per conversation holding the last place given. The upsert takes
     * the row's lock, so two appends to one conversation queue up on it and
     * leave with different places, and a place is not given again.
     */
    private static final String PLACE_TABLE = """
            CREATE TABLE IF NOT EXISTS mc_conversation_entry_seq (
                namespace       TEXT   NOT NULL,
                conversation_id TEXT   NOT NULL,
                last_seq        BIGINT NOT NULL,
                PRIMARY KEY (namespace, conversation_id)
            )""";

    private static final String NEXT_PLACE = """
            INSERT INTO mc_conversation_entry_seq (namespace, conversation_id, last_seq) VALUES (?, ?, 1)
            ON CONFLICT (namespace, conversation_id) DO UPDATE SET last_seq = mc_conversation_entry_seq.last_seq + 1
            RETURNING last_seq""";

    private final DocumentTable<Message> messages;
    private final Sql sql;
    private final ScopeSupplier scope;

    public PgMessageRepository(Sql sql, ScopeSupplier scope) {
        this.sql = sql;
        this.scope = scope;
        this.messages = DocumentTable.of(Message.class)
                .table("mc_conversation_entry")
                .partitionKey("namespace", "TEXT", m -> scope.namespace().value())
                .id("id", "TEXT", m -> m.id().value())
                .requiredColumn("conversation_id", "TEXT", m -> m.conversationId().value())
                .requiredColumn("seq", "BIGINT", Message::sequence)
                .index("namespace", "conversation_id", "seq")
                .build(sql);
    }

    /** Creates the tables where they are missing. */
    public PgMessageRepository initSchema() {
        messages.createSchema();
        sql.execute(PLACE_TABLE);
        return this;
    }

    /** The place and the row in one transaction: a message is stored with its place, or not at all. */
    @Override
    public Message append(ConversationId conversation, LongFunction<Message> create) {
        return sql.inTransaction((Sql tx) -> {
            long place = tx.scalar(NEXT_PLACE, Long.class, scope.namespace().value(), conversation.value());
            Message message = create.apply(place);
            tx.update("INSERT INTO mc_conversation_entry (namespace, id, conversation_id, seq, doc) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    scope.namespace().value(), message.id().value(), conversation.value(), place,
                    tx.json().jsonb(message));
            return message;
        });
    }

    /** Reads the row {@code FOR UPDATE} and writes the change in the same transaction. */
    @Override
    public Optional<Message> update(ConversationId conversation, MessageId id, UnaryOperator<Message> change) {
        return messages.update(scope.namespace().value(), id.value(), change);
    }

    @Override
    public List<Message> findByConversation(ConversationId conversation, PageRequest page) {
        return messages.find("WHERE namespace = ? AND conversation_id = ? ORDER BY seq LIMIT ? OFFSET ?",
                scope.namespace().value(), conversation.value(), page.size(), page.offset());
    }

    @Override
    public Optional<Message> findById(ConversationId conversation, MessageId id) {
        return messages.findOne("WHERE namespace = ? AND conversation_id = ? AND id = ?",
                scope.namespace().value(), conversation.value(), id.value());
    }

    @Override
    public void deleteByConversation(ConversationId conversation) {
        messages.delete("WHERE namespace = ? AND conversation_id = ?", scope.namespace().value(), conversation.value());
    }
}
