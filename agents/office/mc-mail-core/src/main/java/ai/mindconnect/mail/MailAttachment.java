package ai.mindconnect.mail;

/**
 * One file attached to a message, as a list shows it — no content.
 *
 * @param id          how {@link MailStore#attachment} is asked for it: its
 *                    place among the message's attachments on IMAP and Gmail,
 *                    Graph's own attachment id on Outlook
 * @param name        the file name the sender gave it
 * @param contentType its MIME type, {@code application/octet-stream} when unknown
 * @param size        bytes, or -1 when the provider did not say
 */
public record MailAttachment(String id, String name, String contentType, long size) {

    public MailAttachment {
        if (name == null || name.isBlank()) name = "attachment";
        if (contentType == null || contentType.isBlank()) contentType = "application/octet-stream";
    }
}
