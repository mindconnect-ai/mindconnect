package ai.mindconnect.agent.runtime.capabilities.attachment;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import ai.mindconnect.agent.loop.domain.ContentPart;
import ai.mindconnect.agent.loop.domain.ContentPart.MediaSource;
import ai.mindconnect.agent.runtime.api.domain.Session;

/**
 * A file attached to a session.
 *
 * @param id        its id in the file store; {@code null} for a file that
 *                  is only named
 * @param mediaType as it was recorded; {@code null} or unspecific where
 *                  only the name says what the file is
 * @param path      where a copy lies on disk for the session's tools;
 *                  {@code null} for a file that is reached through search
 */
public record Attachment(String id, String name, String mediaType, long sizeBytes, String path) {

    /** The files attached to the session, in the order they were attached. */
    public static List<Attachment> of(Session session) {
        return of(session.metadata());
    }

    /** The files a session's metadata holds as attached. */
    public static List<Attachment> of(Map<String, Object> sessionMetadata) {
        if (!(sessionMetadata.get(AttachmentPlugin.ATTACHMENTS) instanceof List<?> stored)) {
            return List.of();
        }
        return stored.stream()
                .filter(Map.class::isInstance)
                .map(entry -> (Map<?, ?>) entry)
                .map(entry -> new Attachment(
                        entry.get("id") instanceof String id ? id : null,
                        String.valueOf(entry.get("name")),
                        entry.get("mediaType") instanceof String mediaType ? mediaType : null,
                        entry.get("sizeBytes") instanceof Number size ? size.longValue() : 0,
                        entry.get("path") instanceof String path ? path : null))
                .toList();
    }

    boolean isImage() {
        return says() ? mediaType.toLowerCase(Locale.ROOT).startsWith("image/")
                : List.of("png", "jpg", "jpeg", "gif", "webp").contains(extension());
    }

    /** May a model read it with the message — an image or a PDF the file store holds? */
    boolean sendableAsPart() {
        return id != null && (isImage()
                || (says() ? mediaType.equalsIgnoreCase("application/pdf") : extension().equals("pdf")));
    }

    /** The part that carries the file with a message. */
    ContentPart part() {
        MediaSource.FileId file = new MediaSource.FileId(id);
        return isImage() ? new ContentPart.Image(file, ContentPart.Image.Detail.AUTO, name)
                : new ContentPart.Document(file, name);
    }

    /** How the file is named to the model: its name, its kind, and where it is on disk when it has a copy there. */
    String listing() {
        String listed = name + " (" + kind() + ")";
        return path == null ? listed : listed + " — on disk at `" + path + "`";
    }

    /** A word for the file type, enough for a model to stop treating "x.pdf" as a path. */
    String kind() {
        if (isImage()) {
            return "image";
        }
        return switch (extension()) {
            case "pdf" -> "PDF";
            case "doc", "docx" -> "Word document";
            case "xls", "xlsx" -> "Excel sheet";
            case "ppt", "pptx" -> "presentation";
            case "md", "markdown" -> "Markdown";
            case "txt" -> "text file";
            case "" -> "file";
            default -> extension().toUpperCase(Locale.ROOT) + " file";
        };
    }

    private boolean says() {
        return mediaType != null && !mediaType.isBlank() && !mediaType.equalsIgnoreCase("application/octet-stream");
    }

    private String extension() {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
