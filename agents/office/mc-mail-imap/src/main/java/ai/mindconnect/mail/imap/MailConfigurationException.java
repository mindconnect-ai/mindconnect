package ai.mindconnect.mail.imap;

/**
 * The user has not finished setting their mailbox up, or set it up wrongly.
 *
 * <p>Its message is written for the person, not for the log: every tool here
 * returns it to the model as the tool result, so it is the sentence the user
 * ends up reading in the chat. It therefore names the variable and where to
 * put it, and never the password.
 */
public class MailConfigurationException extends RuntimeException {

    public MailConfigurationException(String message) {
        super(message);
    }
}
