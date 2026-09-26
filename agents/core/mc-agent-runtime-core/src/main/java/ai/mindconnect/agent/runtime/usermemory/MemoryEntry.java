package ai.mindconnect.agent.runtime.usermemory;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;

import java.time.Instant;
import java.util.Objects;

/**
 * One thing an agent remembers about a user, across chats. The name is the
 * key within its memory — writing an entry under a name that exists there
 * replaces it — and the description is what stands for the entry in every
 * system prompt, so it is one line; the content is read on demand.
 *
 * @param agentId         whose memory it is: {@code null} for the user's own,
 *                        which every agent with the memory tools shares; an
 *                        agent's id for what that one agent keeps about the
 *                        user — a secretary's, say — which no other agent sees
 * @param sourceSessionId the chat the entry was last written from, or
 *                        {@code null} when it was not written from one
 */
public record MemoryEntry(
        UserId userId,
        AgentId agentId,
        String name,
        MemoryType type,
        String description,
        String content,
        SessionId sourceSessionId,
        Instant createdAt,
        Instant updatedAt
) {

    public MemoryEntry {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        description = description == null ? "" : description;
        content = content == null ? "" : content;
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = createdAt;
    }

    /** An entry of the user's own memory, shared by every agent. */
    public MemoryEntry(UserId userId, String name, MemoryType type, String description, String content,
                       SessionId sourceSessionId, Instant createdAt, Instant updatedAt) {
        this(userId, null, name, type, description, content, sourceSessionId, createdAt, updatedAt);
    }

    /** Whether every agent of the user sees it — not one agent's own. */
    public boolean shared() {
        return agentId == null;
    }
}
