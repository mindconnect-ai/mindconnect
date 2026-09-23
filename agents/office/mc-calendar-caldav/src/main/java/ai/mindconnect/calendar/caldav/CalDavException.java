package ai.mindconnect.calendar.caldav;

/** What the server said, in a sentence the person who owns the calendar can act on. */
public class CalDavException extends RuntimeException {

    public CalDavException(String message) {
        super(message);
    }

    public CalDavException(String message, Throwable cause) {
        super(message, cause);
    }
}
