package ai.mindconnect.common;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record DomainEvent(
        UUID id,
        String channel,
        String type,
        String sourceService,
        String sourceRefId,
        Namespace namespace,
        Map<String, Object> payload,
        Instant occurredAt
) {
    public static DomainEvent of(String channel, String type, String sourceService, String sourceRefId,
                                  Namespace namespace, Map<String, Object> payload) {
        return new DomainEvent(UUID.randomUUID(), channel, type, sourceService, sourceRefId,
                namespace, payload, Instant.now());
    }
}
