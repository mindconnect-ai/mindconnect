package ai.mindconnect.message.adapter.pg;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.jdbc.DocumentTable;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.message.domain.Conversation;
import ai.mindconnect.message.domain.ConversationId;
import ai.mindconnect.message.port.out.ConversationRepository;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link ConversationRepository} on Postgres. One row of
 * {@code mc_conversation} per conversation, keyed by {@code (namespace, id)}:
 * the document, plus the creation time that {@link #findAll} sorts by —
 * newest first, exactly as the file store lists them. The repository is
 * bound to one namespace and every statement matches it.
 */
public final class PgConversationRepository implements ConversationRepository {

    private final DocumentTable<Conversation> conversations;
    private final Namespace namespace;

    public PgConversationRepository(DataSource dataSource, Namespace namespace) {
        this(Sql.of(dataSource), namespace);
    }

    /** Share a {@link Sql} — and with it the application's JSON mapper — with the other stores. */
    public PgConversationRepository(Sql sql, Namespace namespace) {
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.conversations = DocumentTable.of(Conversation.class)
                .table("mc_conversation")
                .partitionKey("namespace", "TEXT", c -> namespace.value())
                .id("id", "TEXT", c -> c.id().value())
                .column("created_at", "TIMESTAMPTZ", Conversation::createdAt)
                .index("namespace", "created_at")
                .build(sql);
    }

    public PgConversationRepository initSchema() {
        conversations.createSchema();
        return this;
    }

    @Override
    public Conversation save(Conversation conversation) {
        return conversations.save(conversation);
    }

    @Override
    public Optional<Conversation> findById(ConversationId id) {
        return conversations.findById(namespace.value(), id.value());
    }

    @Override
    public List<Conversation> findAll(PageRequest page) {
        return conversations.find("WHERE namespace = ? ORDER BY created_at DESC, id LIMIT ? OFFSET ?",
                namespace.value(), page.size(), page.offset());
    }
}
