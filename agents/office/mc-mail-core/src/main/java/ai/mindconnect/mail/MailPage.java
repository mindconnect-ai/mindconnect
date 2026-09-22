package ai.mindconnect.mail;

import ai.mindconnect.mail.MailMessage;

import java.util.List;

/**
 * One page of a folder, and how many there are to page through.
 *
 * @param messages this page, newest first
 * @param total    how many match in all. <b>-1 means the provider did not
 *                 say</b> — a number nobody knows is not a zero, and a pager
 *                 built on a zero would hide the rest of the mailbox
 * @param estimate whether {@code total} is the provider's estimate rather
 *                 than a count. Gmail answers with one, and a pager that
 *                 pretends otherwise ends on a page that is not there
 */
public record MailPage(List<MailMessage> messages, long total, boolean estimate) {

    public MailPage {
        messages = messages == null ? List.of() : List.copyOf(messages);
        if (total < 0) total = -1;
    }

    public static MailPage of(List<MailMessage> messages, long total) {
        return new MailPage(messages, total, false);
    }

    /** For a provider that will not count: the pager then offers no page numbers. */
    public static MailPage uncounted(List<MailMessage> messages) {
        return new MailPage(messages, -1, false);
    }

    public boolean counted() {
        return total >= 0;
    }
}
