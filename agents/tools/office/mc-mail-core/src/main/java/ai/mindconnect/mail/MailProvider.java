package ai.mindconnect.mail;

import ai.mindconnect.agent.tool.ToolConnection;

/**
 * One kind of mailbox: IMAP here, Microsoft Graph and Gmail elsewhere.
 *
 * <p>The seam between what is open and what is not. {@link MailAccounts} asks
 * every provider it finds — through {@code ServiceLoader}, or handed in by a
 * host that builds them itself — so a mail client built on this port reads
 * whatever mailboxes the classpath can open, and a new kind of mailbox is a
 * module rather than a branch in a switch.
 *
 * <p>A provider owns a connection's {@code provider} name: the same name its
 * {@link ai.mindconnect.agent.tool.ConnectionSpec} declares, which is what
 * puts the account card on a user's profile. Nothing here knows a credential:
 * the connection arrives resolved and with its token renewed.
 */
public interface MailProvider {

    /** The connection provider this opens — {@code email}, {@code microsoft}, {@code google}. */
    String provider();

    /**
     * A mailbox on that connection, opened for one request and closed by the
     * caller. Throws {@link MailStoreException} when the connection cannot be
     * used — a missing field, a password the server refuses.
     */
    MailStore open(ToolConnection connection);
}
