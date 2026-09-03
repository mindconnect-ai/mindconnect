package ai.mindconnect.agent.adapter.pg;

import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.domain.SessionStatus;
import ai.mindconnect.agent.domain.view.AgentSessionHeader;
import ai.mindconnect.agent.port.out.AgentSessionRepository;
import ai.mindconnect.common.Namespace;
import ai.mindconnect.jdbc.DocumentTable;
import ai.mindconnect.jdbc.Row;
import ai.mindconnect.jdbc.Sql;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link AgentSessionRepository} on Postgres: one row of {@code mc_agent_session}
 * per session, with the keys every listing filters by — definition, namespace,
 * user, parent — and the start time they sort by, newest first.
 *
 * <p>{@link #deleteById} removes the session row only. The file store deletes
 * the session's whole directory, taking working memory, todo list and
 * workspace files with it; here those live in their own tables and their own
 * repositories, and the caller that ends a session deletes them there.
 */
public final class PgAgentSessionRepository implements AgentSessionRepository {

    private final DocumentTable<AgentSession> sessions;

    public PgAgentSessionRepository(DataSource dataSource) {
        this(Sql.of(dataSource));
    }

    public PgAgentSessionRepository(Sql sql) {
        this.sessions = DocumentTable.of(AgentSession.class)
                .table("mc_agent_session")
                .id("id", "UUID", AgentSession::id)
                .column("agent_definition_id", "UUID", AgentSession::agentDefinitionId)
                .requiredColumn("namespace", "TEXT", s -> s.namespace().value())
                .column("user_id", "TEXT", AgentSession::userId)
                .column("parent_session_id", "UUID", AgentSession::parentSessionId)
                .column("started_at", "TIMESTAMPTZ", AgentSession::startedAt)
                // What the sidebar shows — kept as columns so a list of
                // sessions never deserializes a session.
                .column("conversation_id", "UUID", AgentSession::conversationId)
                .column("title", "TEXT", AgentSession::title)
                .column("status", "TEXT", AgentSession::status)
                .column("completed_at", "TIMESTAMPTZ", AgentSession::completedAt)
                .index("namespace", "user_id", "started_at")
                .index("parent_session_id")
                .build(sql);
    }

    public PgAgentSessionRepository initSchema() {
        sessions.createSchema();
        return this;
    }

    @Override
    public AgentSession save(AgentSession session) {
        return sessions.save(session);
    }

    @Override
    public Optional<AgentSession> findById(UUID id) {
        return sessions.findById(id);
    }

    @Override
    public List<AgentSession> findByAgentDefinitionId(UUID agentDefinitionId, Namespace namespace, String userId) {
        return sessions.find("WHERE agent_definition_id = ? AND namespace = ? AND user_id = ? "
                        + "ORDER BY started_at DESC NULLS LAST, id",
                agentDefinitionId, namespace.value(), userId);
    }

    /** Top-level sessions only — sub-agent sessions are reached through {@link #findByParentSessionId}. */
    @Override
    public List<AgentSession> findByUser(Namespace namespace, String userId) {
        return sessions.find("WHERE namespace = ? AND user_id = ? AND parent_session_id IS NULL "
                        + "ORDER BY started_at DESC NULLS LAST, id",
                namespace.value(), userId);
    }

    /** Headers from the columns alone: the sidebar's list without a single document read. */
    @Override
    public List<Header> findHeadersByUser(Namespace namespace, String userId) {
        return sessions.select(PgAgentSessionRepository::header,
                "WHERE namespace = ? AND user_id = ? AND parent_session_id IS NULL "
                        + "ORDER BY started_at DESC NULLS LAST, id",
                namespace.value(), userId);
    }

    /** {@link AgentSessionHeader} built from the row — every scalar of a session, none of its collections. */
    public record Header(UUID id, UUID agentDefinitionId, Namespace namespace, String userId,
                         UUID conversationId, String title, SessionStatus status,
                         Instant startedAt, Instant completedAt, UUID parentSessionId)
            implements AgentSessionHeader { }

    private static Header header(Row row) throws SQLException {
        return new Header(row.uuid("id"), row.uuid("agent_definition_id"), new Namespace(row.string("namespace")),
                row.string("user_id"), row.uuid("conversation_id"), row.string("title"),
                row.enumValue("status", SessionStatus.class), row.instant("started_at"),
                row.instant("completed_at"), row.uuid("parent_session_id"));
    }

    @Override
    public List<AgentSession> findByParentSessionId(UUID parentSessionId) {
        return sessions.find("WHERE parent_session_id = ? ORDER BY started_at NULLS LAST, id", parentSessionId);
    }

    @Override
    public void deleteById(UUID id) {
        sessions.deleteById(id);
    }
}
