package ai.mindconnect.agent.runtime.api.domain;

import java.util.List;
import java.util.Map;

import ai.mindconnect.agent.loop.domain.ContentPart;

/**
 * What a user asks in a session, and as whom the agent answers. A session
 * belongs to no agent — the agent comes with the turn, so two agents can
 * answer in one session.
 *
 * @param agent the agent of this turn, whole: whoever asks has already
 *              looked it up, and says with its model settings which model
 *              answers; {@code null} to use the session's
 * @param metadata what is known of the input beyond its parts; it is kept
 *                 on the user's message
 */
public record TurnRequest(
        SessionId sessionId,
        List<ContentPart> input,
        AgentDefinition agent,
        Map<String, Object> metadata
) {

    public TurnRequest {
        input = List.copyOf(input);
        metadata = Map.copyOf(metadata);
    }

    /** A text question to the session's default agent, with no settings of its own. */
    public static TurnRequest text(SessionId sessionId, String text) {
        return new TurnRequest(sessionId, List.of(new ContentPart.Text(text)), null, Map.of());
    }
}
