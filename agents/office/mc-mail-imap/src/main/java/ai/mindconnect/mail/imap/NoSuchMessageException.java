package ai.mindconnect.mail.imap;

/**
 * The id names no message in this folder — moved or deleted since it was
 * listed, or never there.
 *
 * <p>Its own type because a caller working through a list of ids treats it
 * differently from every other failure: a message that is gone is an
 * {@code Outcome.Gone}, which a screen takes off its list; a server that
 * refuses is a failure the screen reports. Both were one exception before,
 * and told apart by nobody.
 */
public class NoSuchMessageException extends MailAccessException {

    public NoSuchMessageException(String message) {
        super(message);
    }
}
