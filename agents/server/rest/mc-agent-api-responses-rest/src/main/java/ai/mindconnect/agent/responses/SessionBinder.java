package ai.mindconnect.agent.responses;

import ai.mindconnect.agent.management.api.AgentManagement;
import ai.mindconnect.agent.management.api.SessionManagement;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.protocol.Session;
import ai.mindconnect.agent.protocol.runtime.AgentRuntimeBackend;
import ai.mindconnect.agent.responses.wire.CreateResponseRequest;


/**
 * Finds the session a request belongs to, and makes it run what the request
 * asked for.
 *
 * <p>A conversation in the Responses API is a session here — the same thing
 * under two names: the durable thread a series of responses hangs off. So a
 * client that sends {@code conversation} is naming a session directly, and
 * one that sends {@code previous_response_id} is naming it indirectly, by
 * pointing at something that happened in it. Sending neither means starting
 * a new one.
 */
public final class SessionBinder {

    private final AgentRuntimeBackend backend;
    private final SessionManagement sessions;
    private final AgentManagement agents;

    public SessionBinder(AgentRuntimeBackend backend, SessionManagement sessions, AgentManagement agents) {
        this.backend = backend;
        this.sessions = sessions;
        this.agents = agents;
    }

    public Session bind(CreateResponseRequest request, ModelResolver.Resolution resolution) {
        Session session = existing(request).orElseGet(
                () -> backend.sessions().open(resolution.agentName()));

        if (resolution.overridesModel()) {
            applyModelOverride(session, resolution);
        }
        return session;
    }

    private java.util.Optional<Session> existing(CreateResponseRequest request) {
        String conversationId = request.conversationId();
        if (conversationId != null) {
            return backend.sessions().get(conversationId);
        }
        if (request.previousResponseId() != null) {
            // The response knows the session it ran in, which is what makes
            // previous_response_id enough on its own — the client never has
            // to have seen a conversation id.
            return backend.responses().get(request.previousResponseId())
                    .map(r -> backend.sessions().get(r.sessionId()).orElse(null))
                    .map(java.util.Optional::ofNullable)
                    .orElse(java.util.Optional.empty());
        }
        return java.util.Optional.empty();
    }

    /**
     * The client named an llm-config rather than an agent: the default agent
     * answers, on that model.
     *
     * <p>Written as a session-level override rather than by editing the
     * agent — the agent is shared, and a request must not change what it is
     * for everyone else. The override rides on the same {@link SessionAgentRef}
     * the chat UI writes, so the agent keeps its tools, its prompt and the
     * agents it may call, and only the model moves.
     */
    private void applyModelOverride(Session session, ModelResolver.Resolution resolution) {
        AgentDefinition definition = agents.findByName(resolution.agentName()).orElseThrow(
                () -> new IllegalStateException("The default agent '" + resolution.agentName()
                        + "' does not exist; a request naming an llm-config has nothing to run on."))
                .definition();
        // The session keeps a definition of its own from here on: the default agent, on the model asked for.
        sessions.changeAgent(SessionId.of(session.id()), new AgentDefinition(definition.name(),
                definition.description(), definition.instructions(),
                ModelSettings.ofModel(resolution.llmConfigOverride()).over(definition.modelSettings()),
                definition.tools(), definition.maxRounds(), definition.metadata()));
    }
}
