package ai.mindconnect.agent.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Locale;

/**
 * A file attached to a chat session: the id the file store holds it under,
 * and what the upload said about it. The name is what the model and the user
 * see; the id is what a message part references; the media type decides how
 * the file reaches the model — an image goes with the next message as an
 * image part, a PDF as a document part, everything else through the vector
 * store and {@code vector_search}.
 *
 * <p>Sessions written before this record existed stored bare names; such an
 * entry reads as a file with no id, and is announced but never sent inline.
 */
public record AttachedFile(
        String id,
        String name,
        String mediaType,
        long sizeBytes
) {
    @JsonCreator
    public AttachedFile(@JsonProperty("id") String id,
                        @JsonProperty("name") String name,
                        @JsonProperty("mediaType") String mediaType,
                        @JsonProperty("sizeBytes") long sizeBytes) {
        this.id = id;
        this.name = name;
        this.mediaType = mediaType;
        this.sizeBytes = sizeBytes;
    }

    /** A legacy entry — a session that stored only the file's name. */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static AttachedFile named(String name) {
        return new AttachedFile(null, name, null, 0);
    }

    /** Is this an image — by media type, or by extension when the upload recorded none? */
    @JsonIgnore
    public boolean isImage() {
        if (mediaType != null) return mediaType.toLowerCase(Locale.ROOT).startsWith("image/");
        String ext = extension();
        return ext.equals("png") || ext.equals("jpg") || ext.equals("jpeg")
                || ext.equals("gif") || ext.equals("webp");
    }

    /** Is this a PDF — the one document kind the vision-capable providers read inline? */
    @JsonIgnore
    public boolean isPdf() {
        if (mediaType != null) return mediaType.equalsIgnoreCase("application/pdf");
        return extension().equals("pdf");
    }

    /** Can this file travel as a message part at all — it has an id, and a kind a model may read. */
    @JsonIgnore
    public boolean sendableAsPart() {
        return id != null && (isImage() || isPdf());
    }

    private String extension() {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "" : n.substring(dot + 1);
    }
}
