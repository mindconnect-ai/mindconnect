package ai.mindconnect.agent.service.prompt;

import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.domain.AttachedFile;
import ai.mindconnect.message.domain.ContentPart;

import java.util.ArrayList;
import java.util.List;

/**
 * Which attached files ride along with a user message as content parts.
 * A file attached since the last turn is announced with the next message
 * ({@link AttachmentNotice}); the ones a model may read inline — images and
 * PDFs — go with that message as parts too, so a vision model sees the
 * picture the user just dropped in. Everything else is reached through
 * {@code vector_search}, as before.
 */
public final class AttachmentParts {

    private AttachmentParts() {
    }

    /**
     * The user's parts plus one media part per freshly attached image or
     * PDF, in attach order. {@code fresh} names the files this message
     * announces (see {@link AttachmentNotice#unannounced}); a name the
     * session no longer holds, or a legacy entry without an id, adds nothing.
     */
    public static List<ContentPart> withAttachments(List<ContentPart> parts, AgentSession session,
                                                    List<String> fresh) {
        if (session == null || fresh.isEmpty()) return parts;
        List<ContentPart> out = new ArrayList<>(parts);
        for (String name : fresh) {
            session.attachedFile(name)
                    .filter(AttachedFile::sendableAsPart)
                    .map(AttachmentParts::part)
                    .ifPresent(out::add);
        }
        return List.copyOf(out);
    }

    /** The content part for an attached file the model may read inline. */
    public static ContentPart part(AttachedFile file) {
        return file.isImage()
                ? new ContentPart.Image(file.id(), file.name(), file.effectiveMediaType(), file.sizeBytes())
                : new ContentPart.File(file.id(), file.name(), file.effectiveMediaType(), file.sizeBytes());
    }
}
