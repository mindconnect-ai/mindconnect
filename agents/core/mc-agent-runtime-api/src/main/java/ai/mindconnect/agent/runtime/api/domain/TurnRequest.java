package ai.mindconnect.agent.runtime.api.domain;

import java.util.List;

import ai.mindconnect.agent.loop.domain.ContentPart;

/**
 * What a user asks in a session, and as whom the agent answers. A session
 * belongs to no agent — the agent comes with the turn, so two agents can
 * answer in one session.
 *
 * @param agentName     the agent of this turn, by the name an
 *                      {@code AgentResolver} knows it under; {@code null}
 *                      to use the session's default
 * @param modelSettings the strongest layer of the model settings, for this
 *                      turn only
 */
public record TurnRequest(
        SessionId sessionId,
        List<ContentPart> input,
        String agentName,
        ModelSettings modelSettings
) {

    public TurnRequest {
        input = List.copyOf(input);
    }

    /** A text question to the session's default agent, with no settings of its own. */
    public static TurnRequest text(SessionId sessionId, String text) {
        return new TurnRequest(sessionId, List.of(new ContentPart.Text(text)), null, ModelSettings.NONE);
    }
}
