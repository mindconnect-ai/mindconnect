package ai.mindconnect.agent.protocol.runtime;

import ai.mindconnect.agent.protocol.item.ContentPart;
import ai.mindconnect.agent.protocol.item.ConversationItem;
import ai.mindconnect.agent.protocol.item.Role;
import ai.mindconnect.message.domain.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * Between the protocol's content parts and the conversation record's: a
 * stored image or document becomes a domain part that references it by
 * file id; a stored message reads back as a protocol message whose media
 * parts point at the same ids. Text is text on both sides.
 */
final class ProtocolParts {

    private ProtocolParts() {
    }

    /** The domain part for a stored image. */
    static ai.mindconnect.message.domain.ContentPart image(ai.mindconnect.filestore.StoredFile stored) {
        return new ai.mindconnect.message.domain.ContentPart.Image(
                stored.id().value(), stored.name(), stored.contentType(), stored.size());
    }

    /**
     * A stored message as a protocol message: the text as one text part,
     * each media part as an image or document with a {@code FileId} source.
     * The compressed stub, when there is one, stands in for the text.
     */
    static ConversationItem.Message message(Role role, Message m) {
        String text = m.compressed() && m.compressedContent() != null ? m.compressedContent() : m.content();
        List<ContentPart> parts = new ArrayList<>();
        parts.add(new ContentPart.Text(text == null ? "" : text));
        if (m.parts() != null) {
            for (ai.mindconnect.message.domain.ContentPart part : m.parts()) {
                switch (part) {
                    case ai.mindconnect.message.domain.ContentPart.Text t -> { /* in the text already */ }
                    case ai.mindconnect.message.domain.ContentPart.Image i ->
                            parts.add(ContentPart.Image.of(new ContentPart.MediaSource.FileId(i.fileId())));
                    case ai.mindconnect.message.domain.ContentPart.File f ->
                            parts.add(new ContentPart.Document(
                                    new ContentPart.MediaSource.FileId(f.fileId()), f.name()));
                }
            }
        }
        return new ConversationItem.Message(role, List.copyOf(parts));
    }
}
