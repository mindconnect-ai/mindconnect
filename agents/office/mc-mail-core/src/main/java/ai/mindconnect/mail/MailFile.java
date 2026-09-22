package ai.mindconnect.mail;

/**
 * A file with its content: an attachment read from a message, or one on its
 * way out with a draft.
 *
 * @param name        the file name
 * @param contentType its MIME type, {@code application/octet-stream} when unknown
 * @param data        the bytes
 */
public record MailFile(String name, String contentType, byte[] data) {

    public MailFile {
        if (name == null || name.isBlank()) name = "attachment";
        if (contentType == null || contentType.isBlank()) contentType = "application/octet-stream";
        if (data == null) data = new byte[0];
    }

    public long size() {
        return data.length;
    }
}
