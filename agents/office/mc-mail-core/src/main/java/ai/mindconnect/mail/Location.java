package ai.mindconnect.mail;

import java.util.Objects;

/**
 * Where a message lies: which account, which folder.
 *
 * <p>The one value that reading, deleting and moving need — carried on the
 * message itself, so that nothing above the port has to remember it beside
 * the message or, worse, recover it from a string. Three screens had grown
 * three ways of doing that, and a row named {@code email.freemail:743106578}
 * reached a mailbox that rightly answered it was no message id.
 *
 * @param account  the mailbox id, {@code provider.key} — {@code email.freemail},
 *                 {@code microsoft.work} — as {@link ConnectedMailbox#id()} spells it
 * @param folderId the folder, as {@link MailStore#folders()} named it
 */
public record Location(String account, String folderId) {

    public Location {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(folderId, "folderId");
    }

    public Location inFolder(String otherFolderId) {
        return new Location(account, otherFolderId);
    }
}
