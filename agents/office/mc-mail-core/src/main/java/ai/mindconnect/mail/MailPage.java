package ai.mindconnect.mail;

import java.time.Instant;
import java.util.List;

/**
 * One page of a folder, and how many there are to page through.
 *
 * @param fetched  this page, newest first, each with how the store came by it
 * @param total    how many match in all. <b>-1 means the provider did not
 *                 say</b> — a number nobody knows is not a zero, and a pager
 *                 built on a zero would hide the rest of the mailbox
 * @param estimate whether {@code total} is the provider's estimate rather
 *                 than a count. Gmail answers with one, and a pager that
 *                 pretends otherwise ends on a page that is not there
 * @param asOf     when this page was true — now for a store that asked the
 *                 provider, the cache's last sync for one that did not
 */
public record MailPage(List<Fetched<MailMessage>> fetched, long total, boolean estimate, Instant asOf) {

    public MailPage {
        fetched = fetched == null ? List.of() : List.copyOf(fetched);
        if (total < 0) total = -1;
        if (asOf == null) asOf = Instant.now();
    }

    /** A page straight from the provider, a moment ago. */
    public MailPage(List<MailMessage> messages, long total, boolean estimate) {
        this(live(messages), total, estimate, Instant.now());
    }

    public static MailPage of(List<MailMessage> messages, long total) {
        return new MailPage(messages, total, false);
    }

    /** For a provider that will not count: the pager then offers no page numbers. */
    public static MailPage uncounted(List<MailMessage> messages) {
        return new MailPage(messages, -1, false);
    }

    /** The messages alone, for a caller that does not ask how they were fetched. */
    public List<MailMessage> messages() {
        return fetched.stream().map(Fetched::value).toList();
    }

    private static List<Fetched<MailMessage>> live(List<MailMessage> messages) {
        return messages == null ? List.of() : messages.stream().map(Fetched::live).toList();
    }

    public boolean counted() {
        return total >= 0;
    }
}
