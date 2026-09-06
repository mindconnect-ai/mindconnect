package ai.mindconnect.message.domain;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;
import java.util.stream.Collectors;

/**
 * One piece of a {@link Message}: its text, or an image or file the user sent
 * with it. Media is <em>referenced</em> — by the id the file store gave it —
 * never carried as bytes: a conversation record stays small, and the bytes
 * are read by whoever renders the part (the LLM mapper, the chat UI) through
 * the store, at the moment they are needed.
 *
 * <p>The kinds are closed on purpose. A message's text is what every existing
 * reader sees ({@link Message#content()} mirrors it); the media kinds are what
 * a vision- or document-capable model gets to see in addition. Audio is not a
 * kind yet — it arrives with the speech work, as its own record here.
 *
 * <p>Serialised with a {@code kind} discriminator so the file store, Postgres
 * and the REST API all read a part back as what it was.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ContentPart.Text.class, name = "text"),
        @JsonSubTypes.Type(value = ContentPart.Image.class, name = "image"),
        @JsonSubTypes.Type(value = ContentPart.File.class, name = "file")
})
public sealed interface ContentPart permits ContentPart.Text, ContentPart.Media {

    /** A run of text. */
    record Text(String text) implements ContentPart {
        public Text {
            if (text == null) text = "";
        }
    }

    /**
     * What the media kinds share: the file-store id the bytes live under, and
     * what the upload said about them — enough to render a placeholder
     * ("photo.png, image/png, 240 KB") without touching the store.
     */
    sealed interface Media extends ContentPart permits Image, File {
        String fileId();
        String name();
        String mediaType();
        long sizeBytes();
    }

    /** An image the user sent — for a model that reads images. */
    record Image(String fileId, String name, String mediaType, long sizeBytes) implements Media {}

    /** A document (PDF, …) the user sent — for a model that reads documents. */
    record File(String fileId, String name, String mediaType, long sizeBytes) implements Media {}

    /** The one-part list a plain text message is. */
    static List<ContentPart> text(String text) {
        return List.of(new Text(text));
    }

    /**
     * The text of the parts, joined in order — what {@link Message#content()}
     * holds for a message made of parts. Media parts contribute nothing; a
     * message that is only an image has empty text.
     */
    static String textOf(List<ContentPart> parts) {
        if (parts == null) return "";
        return parts.stream()
                .filter(Text.class::isInstance)
                .map(p -> ((Text) p).text())
                .collect(Collectors.joining("\n"));
    }
}
