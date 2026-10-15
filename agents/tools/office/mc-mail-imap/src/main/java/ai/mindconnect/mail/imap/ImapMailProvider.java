package ai.mindconnect.mail.imap;

import ai.mindconnect.agent.tool.ToolConnection;
import ai.mindconnect.mail.MailProvider;
import ai.mindconnect.mail.MailStore;
import ai.mindconnect.mail.MailStoreException;

/**
 * IMAP and POP3 as a kind of mailbox — the one every mail server speaks.
 *
 * <p>Found by {@code ServiceLoader}: with this module on the classpath a user
 * can attach a mailbox under Connections and every mail tool and screen reads
 * it. {@link MailboxConnectionProvider} is the other half, the card itself.
 */
public class ImapMailProvider implements MailProvider {

    @Override
    public String provider() {
        return MailAccount.PROVIDER;
    }

    @Override
    public MailStore open(ToolConnection connection) {
        try {
            return new ImapMailStore(MailAccount.from(connection));
        } catch (MailConfigurationException e) {
            throw new MailStoreException(e.getMessage(), e);
        }
    }
}
