package ai.mindconnect.calendar.caldav;

/** What the server said, in a sentence the person who owns the calendar can act on. */
public class CalDavException extends RuntimeException {

    /** The HTTP status the server answered with, or 0 when it did not answer at all. */
    private final int status;

    public CalDavException(String message) {
        this(message, 0);
    }

    public CalDavException(String message, int status) {
        super(message);
        this.status = status;
    }

    public CalDavException(String message, Throwable cause) {
        super(message, cause);
        this.status = 0;
    }

    /** The HTTP status behind this, or 0 — a 404 is a question the store can ask again differently. */
    public int status() {
        return status;
    }
}
