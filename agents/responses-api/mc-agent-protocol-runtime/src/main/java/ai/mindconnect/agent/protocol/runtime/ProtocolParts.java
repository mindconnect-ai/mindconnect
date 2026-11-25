package ai.mindconnect.agent.protocol.runtime;

import ai.mindconnect.agent.protocol.item.ContentPart;
import ai.mindconnect.agent.protocol.item.ConversationItem;
import ai.mindconnect.agent.protocol.item.Role;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;

import java.util.ArrayList;
import java.util.List;

/**
 * Between the protocol's content parts and the runtime's: a stored image
 * becomes a part of a turn's input that references it by file id; a message
 * of a session reads back as a protocol message with the same parts. Text is
 * text on both sides.
 */
final class ProtocolParts {

    private ProtocolParts() {
    }

    /** The part of a turn's input for a stored image. */
    static ai.mindconnect.agent.loop.domain.ContentPart image(ai.mindconnect.filestore.StoredFile stored) {
        return new ai.mindconnect.agent.loop.domain.ContentPart.Image(
                new ai.mindconnect.agent.loop.domain.ContentPart.MediaSource.FileId(stored.id().value()),
                ai.mindconnect.agent.loop.domain.ContentPart.Image.Detail.AUTO, stored.name());
    }

    /**
     * A message of a session as a protocol message, part for part. The note
     * the model is shown in a message's place, when there is one, stands in
     * for its text; its media stay.
     */
    static ConversationItem.Message message(Role role, TurnMessage message) {
        List<ContentPart> parts = new ArrayList<>();
        String note = message.metadata().get(SessionStore.COMPRESSED) instanceof String shown ? shown : null;
        if (note != null) {
            parts.add(new ContentPart.Text(note));
        }
        for (ai.mindconnect.agent.loop.domain.ContentPart part : message.content()) {
            switch (part) {
                case ai.mindconnect.agent.loop.domain.ContentPart.Text text -> {
                    if (note == null) {
                        parts.add(new ContentPart.Text(text.text()));
                    }
                }
                case ai.mindconnect.agent.loop.domain.ContentPart.Image image -> parts.add(new ContentPart.Image(
                        source(image.source()), ContentPart.Image.Detail.valueOf(image.detail().name())));
                case ai.mindconnect.agent.loop.domain.ContentPart.Audio audio ->
                        parts.add(new ContentPart.Audio(source(audio.source())));
                case ai.mindconnect.agent.loop.domain.ContentPart.Document document ->
                        parts.add(new ContentPart.Document(source(document.source()), document.name()));
            }
        }
        if (parts.isEmpty()) {
            parts.add(new ContentPart.Text(""));
        }
        return new ConversationItem.Message(role, List.copyOf(parts));
    }

    private static ContentPart.MediaSource source(ai.mindconnect.agent.loop.domain.ContentPart.MediaSource source) {
        return switch (source) {
            case ai.mindconnect.agent.loop.domain.ContentPart.MediaSource.Url url ->
                    new ContentPart.MediaSource.Url(url.url());
            case ai.mindconnect.agent.loop.domain.ContentPart.MediaSource.FileId file ->
                    new ContentPart.MediaSource.FileId(file.fileId());
            case ai.mindconnect.agent.loop.domain.ContentPart.MediaSource.Inline inline ->
                    new ContentPart.MediaSource.Inline(inline.base64Data(), inline.mediaType());
        };
    }
}
