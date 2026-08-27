package ai.mindconnect.agentrest.dto;

import java.util.UUID;

public record StartSessionRequest(UUID agentId, String namespace, String userId) {}
