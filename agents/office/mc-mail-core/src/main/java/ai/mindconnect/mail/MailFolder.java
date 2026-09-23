package ai.mindconnect.mail;

import java.util.Objects;

/**
 * One folder of a mailbox, as the client's left column shows it.
 *
 * <p>Id and name are two things because the three providers disagree about
 * what a folder is called: IMAP has a path ({@code INBOX.Archiv}), Graph has
 * well-known names ({@code sentitems}) and Gmail has label ids
 * ({@code SENT}). The id is what a call passes back; the name is what a
 * person reads, and neither is derivable from the other.
 *
 * @param id      what {@link MailStore} calls take
 * @param name    what the user reads
 * @param unread  how many unread messages it holds, or -1 when the provider
 *                did not say — a count nobody knows is not a zero
 */
public record MailFolder(String id, String name, int unread) {

    public MailFolder {
        Objects.requireNonNull(id, "A folder needs an id");
        if (name == null || name.isBlank()) name = id;
    }

    public static MailFolder of(String id, String name) {
        return new MailFolder(id, name, -1);
    }

    public boolean hasUnreadCount() {
        return unread >= 0;
    }
}
