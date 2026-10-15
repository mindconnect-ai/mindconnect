package ai.mindconnect.mail;

import java.time.Instant;

/**
 * Which messages of a folder a listing wants.
 *
 * <p>The list screen needs two of these — unread only, and a word to look for
 * in sender or subject. An agent needs the rest: "from Anna", "about the
 * offer", "since Monday". Each store turns it into what its provider asks
 * with: an IMAP SEARCH, a Graph {@code $filter}, a Gmail query.
 *
 * @param unreadOnly leave the read ones out
 * @param search     free text matched against sender or subject, or null
 * @param from       only messages whose sender contains this, or null
 * @param subject    only messages whose subject contains this, or null
 * @param since      only messages received on or after this, or null
 * @param before     only messages received before this, or null
 */
public record MailQuery(boolean unreadOnly, String search, String from, String subject,
                        Instant since, Instant before) {

    public MailQuery {
        search = blankToNull(search);
        from = blankToNull(from);
        subject = blankToNull(subject);
    }

    /** Everything. */
    public static MailQuery all() {
        return new MailQuery(false, null, null, null, null, null);
    }

    /** What the list screen asks: unread only, and a word. */
    public static MailQuery of(boolean unreadOnly, String search) {
        return new MailQuery(unreadOnly, search, null, null, null, null);
    }

    /** True when nothing narrows the folder down. */
    public boolean isEmpty() {
        return !unreadOnly && search == null && from == null && subject == null && since == null && before == null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
