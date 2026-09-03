package ai.mindconnect.message.adapter.pg;

import ai.mindconnect.common.Namespace;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.jdbc.DocumentTable;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.message.domain.Conversation;
import ai.mindconnect.message.port.out.ConversationRepository;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link ConversationRepository} on Postgres. One row of
 * {@code mc_conversation} per conversation: the document, plus the namespace
 * and creation time that {@link #findByNamespace} filters and sorts by —
 * newest first, exactly as the file store lists them.
 */
public final class PgConversationRepository implements ConversationRepository {

    private final DocumentTable<Conversation> conversations;

    public PgConversationRepository(DataSource dataSource) {
        this(Sql.of(dataSource));
    }

    /** Share a {@link Sql} — and with it the application's JSON mapper — with the other stores. */
    public PgConversationRepository(Sql sql) {
        this.conversations = DocumentTable.of(Conversation.class)
                .table("mc_conversation")
                .id("id", "UUID", Conversation::id)
                .requiredColumn("namespace", "TEXT", c -> c.namespace().value())
                .column("created_at", "TIMESTAMPTZ", Conversation::createdAt)
                .index("namespace", "created_at")
                .build(sql);
    }

    /** Runs the idempotent DDL ({@code CREATE TABLE IF NOT EXISTS …}). */
    public PgConversationRepository initSchema() {
        conversations.createSchema();
        return this;
    }

    @Override
    public Conversation save(Conversation conversation) {
        return conversations.save(conversation);
    }

    @Override
    public Optional<Conversation> findById(UUID id) {
        return conversations.findById(id);
    }

    @Override
    public List<Conversation> findByNamespace(Namespace namespace, PageRequest page) {
        return conversations.find("WHERE namespace = ? ORDER BY created_at DESC, id LIMIT ? OFFSET ?",
                namespace.value(), page.size(), page.offset());
    }
}
