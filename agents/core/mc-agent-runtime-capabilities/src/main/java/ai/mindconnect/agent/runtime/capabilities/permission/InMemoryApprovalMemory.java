package ai.mindconnect.agent.runtime.capabilities.permission;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.scope.ScopeSupplier;

/** Approvals that live as long as the process. */
public final class InMemoryApprovalMemory implements ApprovalMemory {

    private final Set<String> approved = ConcurrentHashMap.newKeySet();
    private final ScopeSupplier scope;

    public InMemoryApprovalMemory(ScopeSupplier scope) {
        this.scope = scope;
    }

    /** A session id means something only within its namespace. */
    private String key(SessionId sessionId, String toolName) {
        return scope.namespace().value() + "/" + sessionId.value() + "/" + toolName;
    }

    @Override
    public boolean approved(SessionId sessionId, String toolName) {
        return approved.contains(key(sessionId, toolName));
    }

    @Override
    public void remember(SessionId sessionId, String toolName) {
        approved.add(key(sessionId, toolName));
    }
}
