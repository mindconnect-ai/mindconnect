package ai.mindconnect.conversation.domain;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What a participant wrote in a conversation. Who wrote it says what it is:
 * a person's question, a bot's answer, a note of the system.
 *
 * <p>The metadata carries what others keep with a message without the
 * conversation knowing it: the channel's own id of it, or — for a message an
 * agent wrote — its session and turn ({@code agent.sessionId},
 * {@code agent.turnId}).
 *
 * @param sequence its place in the conversation, given by the store as it writes
 * @param from     the participant who wrote it
 * @param content  its text and the images and files sent with it
 */
public record Message(
        MessageId id,
        ConversationId conversationId,
        long sequence,
        ParticipantId from,
        List<ContentPart> content,
        Instant sentAt,
        Map<String, Object> metadata
) {

    public Message {
        content = content == null ? List.of() : List.copyOf(content);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** Its text, the text parts joined in order. */
    public String text() {
        return ContentPart.textOf(content);
    }

    public Message withMetadata(String key, Object value) {
        Map<String, Object> changed = new HashMap<>(metadata);
        changed.put(key, value);
        return new Message(id, conversationId, sequence, from, content, sentAt, changed);
    }
}
