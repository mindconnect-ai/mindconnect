package ai.mindconnect.agent.runtime.adapter.pg;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.domain.ToolApproval;
import ai.mindconnect.agent.runtime.port.out.ToolApprovalRepository;
import ai.mindconnect.jdbc.DocumentTable;
import ai.mindconnect.jdbc.Sql;

import java.util.List;
import java.util.Optional;

/**
 * {@link ToolApprovalRepository} on Postgres: one row of {@code mc_tool_approval}
 * per open question, keyed by {@code (root_session_id, call_id)}. It lives as
 * long as the shared task store whose parked tasks the questions wake, which is
 * why it is installed with that store and, like it, matches no namespace: a
 * session id is unique across namespaces, and the questions are cleaned up from
 * listeners that run outside any request.
 */
public final class PgToolApprovalRepository implements ToolApprovalRepository {

    private final DocumentTable<ToolApproval> approvals;

    public PgToolApprovalRepository(Sql sql) {
        this.approvals = DocumentTable.of(ToolApproval.class)
                .table("mc_tool_approval")
                .partitionKey("root_session_id", "TEXT", a -> a.rootSessionId().value())
                .id("call_id", "TEXT", ToolApproval::callId)
                .requiredColumn("origin_session_id", "TEXT", a -> a.originSessionId().value())
                .requiredColumn("requested_at", "TIMESTAMPTZ", ToolApproval::requestedAt)
                .index("origin_session_id")
                .build(sql);
    }

    public PgToolApprovalRepository initSchema() {
        approvals.createSchema();
        return this;
    }

    @Override
    public boolean saveIfAbsent(ToolApproval approval) {
        return approvals.insert(approval);
    }

    @Override
    public Optional<ToolApproval> find(SessionId rootSessionId, String callId) {
        return approvals.findById(rootSessionId.value(), callId);
    }

    @Override
    public List<ToolApproval> openForRoot(SessionId rootSessionId) {
        return approvals.find("WHERE root_session_id = ? ORDER BY requested_at, call_id", rootSessionId.value());
    }

    @Override
    public void delete(SessionId rootSessionId, String callId) {
        approvals.deleteById(rootSessionId.value(), callId);
    }

    @Override
    public void deleteForRoot(SessionId rootSessionId) {
        approvals.delete("WHERE root_session_id = ?", rootSessionId.value());
    }

    @Override
    public void deleteForSession(SessionId sessionId) {
        approvals.delete("WHERE root_session_id = ? OR origin_session_id = ?", sessionId.value(), sessionId.value());
    }
}
