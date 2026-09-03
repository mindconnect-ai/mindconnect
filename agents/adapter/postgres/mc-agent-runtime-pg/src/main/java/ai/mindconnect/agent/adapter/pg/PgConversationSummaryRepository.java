package ai.mindconnect.agent.adapter.pg;

import ai.mindconnect.agent.memory.domain.ConversationSummary;
import ai.mindconnect.agent.memory.port.out.ConversationSummaryRepository;
import ai.mindconnect.jdbc.DocumentTable;
import ai.mindconnect.jdbc.Sql;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

/**
 * {@link ConversationSummaryRepository} on Postgres: one row of
 * {@code mc_conversation_summary} per summary, read back per conversation in
 * the order of the sequence range each one covers.
 */
public final class PgConversationSummaryRepository implements ConversationSummaryRepository {

    private final DocumentTable<ConversationSummary> summaries;

    public PgConversationSummaryRepository(DataSource dataSource) {
        this(Sql.of(dataSource));
    }

    public PgConversationSummaryRepository(Sql sql) {
        this.summaries = DocumentTable.of(ConversationSummary.class)
                .table("mc_conversation_summary")
                .id("id", "UUID", ConversationSummary::id)
                .requiredColumn("conversation_id", "UUID", ConversationSummary::conversationId)
                .requiredColumn("from_seq", "INTEGER", ConversationSummary::fromSequenceNum)
                .index("conversation_id", "from_seq")
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
    public List<ConversationSummary> findByConversationId(UUID conversationId) {
        return summaries.find("WHERE conversation_id = ? ORDER BY from_seq, id", conversationId);
    }

    @Override
    public void deleteByConversationId(UUID conversationId) {
        summaries.delete("WHERE conversation_id = ?", conversationId);
    }
}
