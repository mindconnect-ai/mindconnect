package ai.mindconnect.agent.runtime.usermemory;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;

import java.time.Instant;
import java.util.Objects;

/**
 * One thing an agent remembers about a user, across chats. The name is the
 * key — writing an entry under a name that exists replaces it — and the
 * description is what stands for the entry in every system prompt, so it
 * is one line; the content is read on demand.
 *
 * @param sourceSessionId the chat the entry was last written from, or
 *                        {@code null} when it was not written from one
 */
public record MemoryEntry(
        UserId userId,
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
}
