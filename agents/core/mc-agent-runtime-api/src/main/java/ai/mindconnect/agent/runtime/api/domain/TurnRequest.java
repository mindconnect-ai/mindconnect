package ai.mindconnect.agent.runtime.api.domain;

import java.util.List;
import java.util.Map;

import ai.mindconnect.agent.loop.domain.ContentPart;

/**
 * What a user asks in a session. Who answers is the session's business: a
 * turn runs as the agent its session has when the turn is accepted, so
 * whoever wants another agent to answer changes the session first.
 *
 * @param metadata what is known of the input beyond its parts; it is kept
 *                 on the user's message
 */
public record TurnRequest(
        SessionId sessionId,
        List<ContentPart> input,
        Map<String, Object> metadata
) {

    public TurnRequest {
        input = List.copyOf(input);
        metadata = Map.copyOf(metadata);
    }

    /** A text question, with nothing more known of it. */
    public static TurnRequest text(SessionId sessionId, String text) {
        return new TurnRequest(sessionId, List.of(new ContentPart.Text(text)), Map.of());
    }
}
