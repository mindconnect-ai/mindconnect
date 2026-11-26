package ai.mindconnect.agent.runtime.llm;

import java.util.Base64;

import ai.mindconnect.agent.loop.domain.ContentPart;
import ai.mindconnect.agent.loop.domain.ContentPart.MediaSource;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.runtime.api.domain.ModelRequest;
import ai.mindconnect.agent.runtime.api.port.out.MediaReader;
import ai.mindconnect.llm.management.api.domain.LlmCapability;
import ai.mindconnect.llm.management.api.domain.LlmConfig;
import ai.mindconnect.llm.runtime.api.domain.LlmContent;

/**
 * The parts of a user's message as one model reads them in one request.
 * Inline media goes as it is. A stored file is read and sent when the model
 * reads that kind and the message belongs to the turn underway; otherwise a
 * note stands in for it. A request repeats the whole history, and an image
 * repeated in every request costs its tokens every time — once the turn
 * that brought it is over, the note names it and the model can ask for it
 * again.
 */
final class MediaParts {

    /** Largest file sent inline; above it the note says so. */
    static final long MAX_INLINE_BYTES = 20L * 1024 * 1024;

    /** The tool that shows an earlier attachment again — named in the note when the agent has it. */
    private static final String VIEWER_TOOL = "view_attachment";

    private final MediaReader media;
    private final LlmConfig model;
    private final String turnId;
    private final boolean viewerOffered;

    /** @param model the configuration of the model asked; {@code null} where nothing is known of it */
    MediaParts(MediaReader media, LlmConfig model, ModelRequest request) {
        this.media = media;
        this.model = model;
        this.turnId = request.turnId().value();
        this.viewerOffered = request.tools().stream().anyMatch(tool -> VIEWER_TOOL.equals(tool.name()));
    }

    LlmContent llmContent(ContentPart part, TurnMessage message) {
        return switch (part) {
            case ContentPart.Text text -> new LlmContent.Text(text.text());
            case ContentPart.Image image when image.source() instanceof MediaSource.Inline inline ->
                    new LlmContent.Image(inline.base64Data(), inline.mediaType());
            case ContentPart.Document document when document.source() instanceof MediaSource.Inline inline ->
                    new LlmContent.Document(inline.base64Data(), inline.mediaType(), document.name());
            case ContentPart.Image image when image.source() instanceof MediaSource.FileId file ->
                    stored(file, "image", image.name(), LlmCapability.VISION, message);
            case ContentPart.Document document when document.source() instanceof MediaSource.FileId file ->
                    stored(file, "document", document.name(), LlmCapability.DOCUMENTS, message);
            default -> throw new IllegalArgumentException(
                    "This gateway sends text, images and documents, inline or from the file store; it cannot send "
                            + part.getClass().getSimpleName());
        };
    }

    private LlmContent stored(MediaSource.FileId file, String kind, String name, LlmCapability needed,
                              TurnMessage message) {
        String what = kind + " attached" + (name == null ? "" : ": " + name);
        if (model == null || !model.supports(needed)) {
            return note(what, "this model does not read " + kind + "s, so it is not included");
        }
        if (!turnId.equals(message.turnId())) {
            return note(what, viewerOffered
                    ? "sent in an earlier turn, not resent; call " + VIEWER_TOOL + "(\"" + name + "\") to see it again"
                    : "sent in an earlier turn, not resent");
        }
        MediaSource.Inline content = media.read(file).orElse(null);
        if (content == null) {
            return note(what, "no longer available");
        }
        if (Base64.getDecoder().decode(content.base64Data()).length > MAX_INLINE_BYTES) {
            return note(what, "too large to send inline");
        }
        return needed == LlmCapability.VISION
                ? new LlmContent.Image(content.base64Data(), content.mediaType())
                : new LlmContent.Document(content.base64Data(), content.mediaType(), name);
    }

    /** What stands in for media the model does not get, marked so it is not taken for something the user typed. */
    private static LlmContent.Text note(String what, String reason) {
        return new LlmContent.Text("[System note — " + what + " — " + reason + ".]");
    }
}
