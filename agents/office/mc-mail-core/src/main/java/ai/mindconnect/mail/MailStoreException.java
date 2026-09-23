package ai.mindconnect.mail;

/**
 * A mailbox could not be reached, or refused what was asked of it.
 *
 * <p>Carries a sentence meant for the user, not for a log: the client shows
 * it as it is, in the pane where the failure happened. No message ever
 * carries a password.
 */
public class MailStoreException extends RuntimeException {

    public MailStoreException(String message) {
        super(message);
    }

    public MailStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
