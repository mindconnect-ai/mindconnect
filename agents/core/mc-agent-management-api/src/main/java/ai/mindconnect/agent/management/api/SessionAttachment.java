package ai.mindconnect.agent.management.api;

import java.util.List;
import java.util.Locale;

/**
 * A file attached to a session.
 *
 * @param id        its id in the file store; {@code null} for a file that
 *                  is only named
 * @param name      what the file is called in the session; a session holds
 *                  one file of a name
 * @param mediaType as it was recorded; {@code null} where it is not known
 * @param path      where a copy lies on disk for the session's tools;
 *                  {@code null} for a file without one
 */
public record SessionAttachment(String id, String name, String mediaType, long sizeBytes, String path) {

    /**
     * By media type, or by extension when the upload recorded none or only
     * the generic {@code application/octet-stream} — what curl and some
     * drag-and-drop sources send for anything.
     */
    public boolean isImage() {
        return says() ? mediaType.toLowerCase(Locale.ROOT).startsWith("image/")
                : List.of("png", "jpg", "jpeg", "gif", "webp").contains(extension());
    }

    /** The one document kind the vision-capable providers read inline. */
    public boolean isPdf() {
        return says() ? mediaType.equalsIgnoreCase("application/pdf") : extension().equals("pdf");
    }

    public SessionAttachment withPath(String path) {
        return new SessionAttachment(id, name, mediaType, sizeBytes, path);
    }

    private boolean says() {
        return mediaType != null && !mediaType.isBlank() && !mediaType.equalsIgnoreCase("application/octet-stream");
    }

    private String extension() {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
