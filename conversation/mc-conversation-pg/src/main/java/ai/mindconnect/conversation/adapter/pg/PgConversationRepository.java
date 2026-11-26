package ai.mindconnect.conversation.adapter.pg;

import ai.mindconnect.scope.TenantScope;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.jdbc.DocumentTable;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.conversation.domain.Conversation;
import ai.mindconnect.conversation.domain.ConversationId;
import ai.mindconnect.conversation.port.out.ConversationRepository;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link ConversationRepository} on Postgres. One row of
 * {@code mc_conversation_thread} per conversation, keyed by
 * {@code (namespace, id)}: the document with its participants, plus the
 * creation time that {@link #findAll} sorts by — newest first, as the file
 * store lists them. Every statement matches the namespace the scope names for
 * the current call. Not {@code mc_conversation}: there the runtime kept its
 * sessions as conversations before, and those rows stay as they were written.
 */
public final class PgConversationRepository implements ConversationRepository {

    private final DocumentTable<Conversation> conversations;
    private final TenantScope scope;

    /** On a {@link Sql} shared with the other stores, and with it the application's JSON mapper. */
    public PgConversationRepository(Sql sql, TenantScope scope) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.conversations = DocumentTable.of(Conversation.class)
                .table("mc_conversation_thread")
                .partitionKey("namespace", "TEXT", c -> scope.tenant().namespace())
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
        return conversations.findById(scope.tenant().namespace(), id.value());
    }

    @Override
    public List<Conversation> findAll(PageRequest page) {
        return conversations.find("WHERE namespace = ? ORDER BY created_at DESC, id LIMIT ? OFFSET ?",
                scope.tenant().namespace(), page.size(), page.offset());
    }

    @Override
    public void deleteById(ConversationId id) {
        conversations.delete("WHERE namespace = ? AND id = ?", scope.tenant().namespace(), id.value());
    }
}
