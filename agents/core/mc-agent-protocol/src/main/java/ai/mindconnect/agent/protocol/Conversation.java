package ai.mindconnect.agent.protocol;

import java.time.Instant;

/**
 * A durable, append-only item log with its own identity — the state behind
 * responses, independent of any execution (concept 9). Knows nothing about
 * agents: the agent binding (definition, tools, memory state) is the session,
 * which is deliberately NOT part of this public protocol.
 *
 * @param namespace tenant scope, as plain string to keep this module
 *                  dependency-free
 */
public record Conversation(String id, String namespace, Instant createdAt) {}
