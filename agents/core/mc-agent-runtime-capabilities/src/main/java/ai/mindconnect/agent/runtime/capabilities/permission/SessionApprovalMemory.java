package ai.mindconnect.agent.runtime.capabilities.permission;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import ai.mindconnect.agent.runtime.api.domain.SessionId;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;

/**
 * Approvals kept in the session's own metadata: they last as long as the
 * session does, on whatever the session store writes to, and need no store
 * of their own.
 */
public final class SessionApprovalMemory implements ApprovalMemory {

    /** The key in a session's metadata: the names of the tools the user approved for the rest of the session. */
    public static final String APPROVED_TOOLS = "approvedTools";

    private final Supplier<SessionStore> sessions;

    public SessionApprovalMemory(Supplier<SessionStore> sessions) {
        this.sessions = sessions;
    }

    @Override
    public boolean approved(SessionId sessionId, String toolName) {
        return sessions.get().find(sessionId)
                .map(session -> session.metadata().get(APPROVED_TOOLS) instanceof List<?> approved
                        && approved.contains(toolName))
                .orElse(false);
    }

    @Override
    public void remember(SessionId sessionId, String toolName) {
        sessions.get().update(sessionId, session -> {
            List<Object> approved = new ArrayList<>(
                    session.metadata().get(APPROVED_TOOLS) instanceof List<?> before ? before : List.of());
            if (!approved.contains(toolName)) {
                approved.add(toolName);
            }
            return session.withMetadata(APPROVED_TOOLS, approved);
        });
    }
}
