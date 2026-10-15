package ai.mindconnect.mail.imap;

/**
 * The mailbox is configured, but this call did not work — a refused password,
 * a server that cannot be reached, a message that is not there any more.
 *
 * <p>Like {@link MailConfigurationException}, its message is written for the
 * person reading the chat and never carries the password.
 */
public class MailAccessException extends RuntimeException {

    public MailAccessException(String message) {
        super(message);
    }

    public MailAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
