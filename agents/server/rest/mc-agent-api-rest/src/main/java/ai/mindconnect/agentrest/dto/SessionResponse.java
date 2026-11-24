package ai.mindconnect.agentrest.dto;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import ai.mindconnect.agent.runtime.api.domain.Turn;
import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.management.api.ManagedSession;

/**
 * A session as the REST API answers with it: who owns it, which agent it
 * runs, where it works. Not the agent's definition — that is asked for under
 * {@code /agents}.
 *
 * @param parentId the session that opened this one; {@code null} for a chat
 * @param agentId    the stored agent it runs; {@code null} for a session on a definition of its own
 * @param turnId     the turn that has not ended in the session; {@code null} when it is idle
 * @param turnStatus where that turn stands — {@code QUEUED}, {@code RUNNING} and {@code WAITING}
 *                   mean the session is busy, {@code INTERRUPTED} that it waits for an answer.
 *                   Read from the turn when the session is asked for and never stored, so a
 *                   client that opens while a turn runs sees it without having heard it begin
 */
public record SessionResponse(String id, String userId, String parentId, String agentId, String title, String type,
                              String status, String workingDir, List<String> additionalDirs, Instant createdAt,
                              String turnId, String turnStatus) {

    public static SessionResponse of(ManagedSession session, Optional<Turn> running) {
        return new SessionResponse(session.id().value(),
                session.userId() == null ? null : session.userId().value(),
                session.parentId() == null ? null : session.parentId().value(),
                session.agentId().map(AgentId::value).orElse(null),
                session.title(), session.type(),
                session.status() == null ? null : session.status().name(),
                session.directories().working(), session.directories().additional(), session.createdAt(),
                running.map(turn -> turn.id().value()).orElse(null),
                running.map(turn -> turn.status().name()).orElse(null));
    }
}
