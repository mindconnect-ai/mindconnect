package ai.mindconnect.mail;

import java.time.Instant;
import java.util.List;

/**
 * One message as the tools hand it to the model: the header fields worth
 * deciding on, and — when it was read in full — its text.
 *
 * @param id          how a later call names this message: the IMAP UID, or the
 *                    message number on POP3, which has no UIDs a folder can look up
 * @param folder      where it was found
 * @param subject     never null; an empty subject becomes "(no subject)"
 * @param from        the sender, as written
 * @param to          the recipients, as written
 * @param receivedAt  when the server received it; null when it did not say
 * @param seen        whether the mailbox has it flagged as read (always false on POP3)
 * @param hasAttachments whether at least one part is an attachment
 * @param attachments the attachments' file names, empty when there are none
 * @param body        the plain text of the message; null on a listing, which reads no bodies
 * @param truncated   true when {@link #body()} was cut to the length the call asked for
 */
public record MailMessage(
        String id,
        String folder,
        String subject,
        String from,
        List<String> to,
        Instant receivedAt,
        boolean seen,
        boolean hasAttachments,
        List<String> attachments,
        String body,
        boolean truncated
) {

    public MailMessage {
        to = to == null ? List.of() : List.copyOf(to);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        if (subject == null || subject.isBlank()) subject = "(no subject)";
    }

    /**
     * This header with {@code preview} as its body, marked as cut: what a
     * listing shows under the subject. Blank leaves the header alone.
     *
     * <p>Every provider has a preview of its own — Graph's {@code bodyPreview},
     * Gmail's {@code snippet}, the first characters of an IMAP body — and each
     * lands here.
     */
    public MailMessage withPreview(String preview) {
        if (preview == null || preview.isBlank()) return this;
        return new MailMessage(id, folder, subject, from, to, receivedAt, seen, hasAttachments,
                attachments, preview, true);
    }
}
