package ai.mindconnect.agentrest.dto;

import java.time.Instant;
import java.util.Map;

import ai.mindconnect.agent.loop.domain.TurnMessageConverter;
import ai.mindconnect.agent.management.api.ManagedMessage;

/**
 * A message of a session as the REST API answers with it: its place, when it
 * was kept, and the message as the turn wrote it. The message is written the
 * way the runtime keeps it in a session file ({@link TurnMessageConverter}),
 * so a client reads it back with the same converter, and a content part says
 * which kind it is.
 *
 * @param sequence       its place in the session; what removing a range of messages is said with
 * @param message        {@code id}, {@code turnId}, {@code type}, {@code content} (parts with
 *                       {@code kind}), {@code toolCalls} and {@code metadata}
 * @param durationMillis how long producing it took; {@code null} where nothing was measured
 * @param tokens         what it takes of a model's context; {@code null} where it was not counted
 */
public record SessionMessageResponse(String id, int sequence, Instant sentAt, Map<String, Object> message,
                                     Long durationMillis, Integer tokens) {

    private static final TurnMessageConverter MESSAGES = new TurnMessageConverter();

    public static SessionMessageResponse of(ManagedMessage stored) {
        return new SessionMessageResponse(stored.id(), stored.sequence(), stored.sentAt(),
                MESSAGES.toJson(stored.message()), stored.durationMillis(), stored.tokens());
    }
}
