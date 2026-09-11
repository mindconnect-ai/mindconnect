package ai.mindconnect.agent.runtime.adapter.pg;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.domain.SessionStatus;
import ai.mindconnect.agent.runtime.domain.view.AgentSessionHeader;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;
import ai.mindconnect.jdbc.DocumentTable;
import ai.mindconnect.jdbc.Row;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.message.domain.ConversationId;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link AgentSessionRepository} on Postgres: one row of {@code mc_agent_session}
 * per session, keyed by {@code (namespace, id)}, with the keys every listing
 * filters by — definition, user, parent — and the start time they sort by,
 * newest first. The repository is bound to one namespace and every statement
 * matches it.
 *
 * <p>{@link #deleteById} removes the session row only. The file store deletes
 * the session's whole directory, taking working memory, todo list and
 * workspace files with it; here those live in their own tables and their own
 * repositories, and the caller that ends a session deletes them there.
 */
public final class PgAgentSessionRepository implements AgentSessionRepository {

    private final DocumentTable<AgentSession> sessions;
    private final Namespace namespace;

    public PgAgentSessionRepository(DataSource dataSource, Namespace namespace) {
        this(Sql.of(dataSource), namespace);
    }

    public PgAgentSessionRepository(Sql sql, Namespace namespace) {
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.sessions = DocumentTable.of(AgentSession.class)
                .table("mc_agent_session")
                .partitionKey("namespace", "TEXT", s -> namespace.value())
                .id("id", "TEXT", s -> s.id().value())
                .column("agent_definition_id", "TEXT", s -> s.agentDefinitionId().value())
                .column("user_id", "TEXT", s -> s.userId().value())
                .column("parent_session_id", "TEXT", s -> s.parentSessionId() == null ? null : s.parentSessionId().value())
                .column("started_at", "TIMESTAMPTZ", AgentSession::startedAt)
                .column("conversation_id", "TEXT", s -> s.conversationId().value())
                .column("title", "TEXT", AgentSession::title)
                .column("status", "TEXT", AgentSession::status)
                .column("completed_at", "TIMESTAMPTZ", AgentSession::completedAt)
                .index("namespace", "user_id", "started_at")
                .index("namespace", "parent_session_id")
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
    public Optional<AgentSession> findById(SessionId id) {
        return sessions.findById(namespace.value(), id.value());
    }

    @Override
    public List<AgentSession> findByAgent(AgentId agent, UserId user) {
        return sessions.find("WHERE namespace = ? AND agent_definition_id = ? AND user_id = ? "
                        + "ORDER BY started_at DESC NULLS LAST, id",
                namespace.value(), agent.value(), user.value());
    }

    /** Top-level sessions only — sub-agent sessions are reached through {@link #findByParentSession}. */
    @Override
    public List<AgentSession> findByUser(UserId user) {
        return sessions.find("WHERE namespace = ? AND user_id = ? AND parent_session_id IS NULL "
                        + "ORDER BY started_at DESC NULLS LAST, id",
                namespace.value(), user.value());
    }

    /** Headers from the columns alone: the sidebar's list without a single document read. */
    @Override
    public List<Header> findHeadersByUser(UserId user) {
        return sessions.select(PgAgentSessionRepository::header,
                "WHERE namespace = ? AND user_id = ? AND parent_session_id IS NULL "
                        + "ORDER BY started_at DESC NULLS LAST, id",
                namespace.value(), user.value());
    }

    /** {@link AgentSessionHeader} built from the row — every scalar of a session, none of its collections. */
    public record Header(SessionId id, AgentId agentDefinitionId, UserId userId,
                         ConversationId conversationId, String title, SessionStatus status,
                         Instant startedAt, Instant completedAt, SessionId parentSessionId)
            implements AgentSessionHeader { }

    private static Header header(Row row) throws SQLException {
        String parent = row.string("parent_session_id");
        return new Header(SessionId.of(row.string("id")),
                AgentId.of(row.string("agent_definition_id")),
                UserId.of(row.string("user_id")),
                ConversationId.of(row.string("conversation_id")),
                row.string("title"), row.enumValue("status", SessionStatus.class),
                row.instant("started_at"), row.instant("completed_at"),
                parent == null ? null : SessionId.of(parent));
    }

    @Override
    public List<AgentSession> findByParentSession(SessionId parent) {
        return sessions.find("WHERE namespace = ? AND parent_session_id = ? ORDER BY started_at NULLS LAST, id",
                namespace.value(), parent.value());
    }

    @Override
    public void deleteById(SessionId id) {
        sessions.deleteById(namespace.value(), id.value());
    }
}
