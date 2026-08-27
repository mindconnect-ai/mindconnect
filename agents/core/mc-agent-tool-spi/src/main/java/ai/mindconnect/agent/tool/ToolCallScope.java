package ai.mindconnect.agent.tool;

import ai.mindconnect.common.Namespace;

import java.util.UUID;

/**
 * Per-invocation context passed to a {@link ToolFactory} when resolving a
 * concrete {@link Tool} for one agent call.
 */
public record ToolCallScope(
        Namespace namespace,
        String userId,
        UUID sessionId,
        UUID agentDefinitionId
) {}
