package ai.mindconnect.calendar;

/**
 * Something the calendar could not do, in words meant for the person looking
 * at the screen rather than for a log.
 */
public class CalendarStoreException extends RuntimeException {

    public CalendarStoreException(String message) {
        super(message);
    }

    public CalendarStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
