package ai.mindconnect.agent.runtime.adapter.pg;

import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.runtime.memory.domain.ConversationSummary;
import ai.mindconnect.agent.runtime.memory.port.out.ConversationSummaryRepository;
import ai.mindconnect.jdbc.DocumentTable;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.message.domain.ConversationId;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;

/**
 * {@link ConversationSummaryRepository} on Postgres: one row of
 * {@code mc_conversation_summary} per summary, keyed by {@code (namespace, id)},
 * read back per conversation in the order of the sequence range each one
 * covers. Every statement matches the namespace the scope names for the
 * current call.
 */
public final class PgConversationSummaryRepository implements ConversationSummaryRepository {

    private final DocumentTable<ConversationSummary> summaries;
    private final ScopeSupplier scope;

    public PgConversationSummaryRepository(DataSource dataSource, ScopeSupplier scope) {
        this(Sql.of(dataSource), scope);
    }

    public PgConversationSummaryRepository(Sql sql, ScopeSupplier scope) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.summaries = DocumentTable.of(ConversationSummary.class)
                .table("mc_conversation_summary")
                .partitionKey("namespace", "TEXT", s -> scope.namespace().value())
                .id("id", "TEXT", s -> s.id().value())
                .requiredColumn("conversation_id", "TEXT", s -> s.conversationId().value())
                .requiredColumn("from_seq", "INTEGER", ConversationSummary::fromSequenceNum)
                .index("namespace", "conversation_id", "from_seq")
                .build(sql);
    }

    public PgConversationSummaryRepository initSchema() {
        summaries.createSchema();
        return this;
    }

    @Override
    public void save(ConversationSummary summary) {
        summaries.save(summary);
    }

    @Override
    public List<ConversationSummary> findByConversation(ConversationId conversation) {
        return summaries.find("WHERE namespace = ? AND conversation_id = ? ORDER BY from_seq, id",
                scope.namespace().value(), conversation.value());
    }

    @Override
    public void deleteByConversation(ConversationId conversation) {
        summaries.delete("WHERE namespace = ? AND conversation_id = ?", scope.namespace().value(), conversation.value());
    }
}
