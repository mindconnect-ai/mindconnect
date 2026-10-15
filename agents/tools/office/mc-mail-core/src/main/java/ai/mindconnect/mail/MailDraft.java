package ai.mindconnect.mail;

import java.util.List;

/**
 * A message on its way out, while it is still the user's to change.
 *
 * <p>It exists as a value rather than as form fields because two things
 * happen to it between Send and sent: the pre-send agent reads it and may
 * hand back a rewritten one, and the store turns it into whatever its
 * provider wants. Both want the message, neither wants the form.
 *
 * @param to      recipients; a message without one cannot be sent
 * @param cc      copy recipients, possibly empty
 * @param subject the subject line, possibly blank
 * @param body    the text, always plain. For an HTML message it is the same
 *                message reduced to its words, which is what a provider sends
 *                as the plain alternative and what a reader without HTML sees
 * @param html    the message as HTML, or null for a plain one. Already
 *                sanitised by whoever built the draft — a store sends it as
 *                it is
 * @param attachments files that go with it, possibly none
 */
public record MailDraft(List<String> to, List<String> cc, String subject, String body, String html,
                        List<MailFile> attachments) {

    public MailDraft {
        to = to == null ? List.of() : to.stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
        cc = cc == null ? List.of() : cc.stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
        subject = subject == null ? "" : subject.strip();
        body = body == null ? "" : body;
        if (html != null && html.isBlank()) html = null;
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    public MailDraft(List<String> to, List<String> cc, String subject, String body, String html) {
        this(to, cc, subject, body, html, List.of());
    }

    /** A plain-text draft. */
    public MailDraft(List<String> to, List<String> cc, String subject, String body) {
        this(to, cc, subject, body, null);
    }

    public static MailDraft empty() {
        return new MailDraft(List.of(), List.of(), "", "");
    }

    /** The same draft with a different plain body — and no HTML, which would now say something else. */
    public MailDraft withBody(String newBody) {
        return new MailDraft(to, cc, subject, newBody, null, attachments);
    }

    /** The same draft as HTML, with {@code text} as its plain alternative. */
    public MailDraft withHtml(String newHtml, String text) {
        return new MailDraft(to, cc, subject, text, newHtml, attachments);
    }

    /** The same draft with these files attached instead of whatever it had. */
    public MailDraft withAttachments(List<MailFile> files) {
        return new MailDraft(to, cc, subject, body, html, files);
    }

    /** All attachments together, in bytes. */
    public long attachmentBytes() {
        return attachments.stream().mapToLong(MailFile::size).sum();
    }

    public boolean isHtml() {
        return html != null;
    }

    /** Addresses as one comma-separated line, which is how a form field holds them. */
    public static List<String> split(String line) {
        if (line == null || line.isBlank()) return List.of();
        return List.of(line.split("[,;]")).stream()
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public String toLine() {
        return String.join(", ", to);
    }

    public String ccLine() {
        return String.join(", ", cc);
    }
}
