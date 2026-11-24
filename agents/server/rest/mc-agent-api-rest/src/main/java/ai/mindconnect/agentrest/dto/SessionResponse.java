package ai.mindconnect.agentrest.dto;

import java.time.Instant;
import java.util.List;

import ai.mindconnect.agent.management.api.AgentId;
import ai.mindconnect.agent.management.api.ManagedSession;

/**
 * A session as the REST API answers with it: who owns it, which agent it
 * runs, where it works. Not the agent's definition — that is asked for under
 * {@code /agents}.
 *
 * @param parentId the session that opened this one; {@code null} for a chat
 * @param agentId  the stored agent it runs; {@code null} for a session on a definition of its own
 */
public record SessionResponse(String id, String userId, String parentId, String agentId, String title, String type,
                              String status, String workingDir, List<String> additionalDirs, Instant createdAt) {

    public static SessionResponse of(ManagedSession session) {
        return new SessionResponse(session.id().value(),
                session.userId() == null ? null : session.userId().value(),
                session.parentId() == null ? null : session.parentId().value(),
                session.agentId().map(AgentId::value).orElse(null),
                session.title(), session.type(),
                session.status() == null ? null : session.status().name(),
                session.directories().working(), session.directories().additional(), session.createdAt());
    }
}
