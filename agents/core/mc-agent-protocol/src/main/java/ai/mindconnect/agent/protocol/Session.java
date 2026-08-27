package ai.mindconnect.agent.protocol;

import java.time.Instant;

/**
 * The binding of a conversation to an agent — the missing link between the
 * state side ({@link Conversation}: a bare item log) and the execution side
 * ({@link Response}: one run).
 *
 * <p>This is where the platform deliberately differs from OpenAI: there,
 * every request carries {@code model}, {@code instructions} and {@code tools}
 * itself. Here, an <b>agent</b> is a named server-side configuration (system
 * prompt, model, tool set, memory strategy — the {@code AgentDefinition},
 * managed through the admin domain, referenced by name). Opening a session
 * binds a conversation to that configuration once; every subsequent
 * {@code ResponseRequest} is just {@code sessionId + input}.
 *
 * <p>This record is the <em>protocol view</em>: identity and binding only.
 * Runtime state that also lives on the session (activated tools, memory
 * summaries, working memory, attached files) is core domain and not part of
 * the public surface.
 */
public record Session(
        String id,
        String namespace,
        String conversationId,
        String agentName,
        Instant createdAt
) {}
