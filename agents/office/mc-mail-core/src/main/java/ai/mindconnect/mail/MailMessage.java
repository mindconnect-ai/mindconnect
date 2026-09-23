package ai.mindconnect.mail;

import java.time.Instant;
import java.util.List;

/**
 * One message as the tools hand it to the model: the header fields worth
 * deciding on, and — when it was read in full — its text.
 *
 * @param id          how a later call names this message: the IMAP UID, or the
 *                    message number on POP3, which has no UIDs a folder can look up
 * @param location    where it lies — the account and the folder, as the store that read it knows them
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
        Location location,
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
        return new MailMessage(id, location, subject, from, to, receivedAt, seen, hasAttachments,
                attachments, preview, true);
    }

    /** The same message, lying somewhere else — after a move. */
    public MailMessage at(Location where) {
        return new MailMessage(id, where, subject, from, to, receivedAt, seen, hasAttachments,
                attachments, body, truncated);
    }

    /** Where it lies and what it is called there — enough to find it again. */
    public Ref ref() {
        return new Ref(location, id);
    }

    /**
     * A message by address: its place and its id there. What a list keeps of
     * a message it does not hold, and what a row on a screen is named after.
     */
    public record Ref(Location location, String id) {

        public Ref {
            java.util.Objects.requireNonNull(location, "location");
            java.util.Objects.requireNonNull(id, "id");
        }

        /**
         * The one spelling of "this message" for a row: account and id. Only
         * ever produced here, and read back by nobody — a screen that has a
         * row id asks the list that drew it where the message is.
         */
        public String rowId() {
            return location.account() + ":" + id;
        }
    }
}
