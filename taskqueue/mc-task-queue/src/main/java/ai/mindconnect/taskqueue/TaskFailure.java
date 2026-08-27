package ai.mindconnect.taskqueue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;

/**
 * Why an attempt failed — captured by the QUEUE, not by the worker. A worker
 * throws and is done; recording what happened is the queue's job, so nothing
 * is lost because someone forgot a try/catch.
 *
 * <p>Kept on the record through a retry as well, so the last failure is still
 * readable while the task waits for its next attempt.
 *
 * @param type       the exception's class name, or {@code null} when the
 *                   failure did not come from an exception (a sweep, say)
 * @param message    short, for lists and logs
 * @param stackTrace the full trace, truncated — diagnostics belong in the
 *                   record a dispatcher UI can show, not only in a log file
 *                   on whichever node happened to run the attempt
 * @param attempt    which attempt this was
 */
public record TaskFailure(
        String type,
        String message,
        String stackTrace,
        int attempt,
        Instant at
) {

    /** Traces are for reading, not for archiving — anything beyond this is noise. */
    private static final int MAX_TRACE_CHARS = 8_000;

    public static TaskFailure of(Throwable cause, int attempt) {
        StringWriter trace = new StringWriter();
        cause.printStackTrace(new PrintWriter(trace));
        String full = trace.toString();
        return new TaskFailure(
                cause.getClass().getName(),
                cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName(),
                full.length() > MAX_TRACE_CHARS ? full.substring(0, MAX_TRACE_CHARS) + "\n… truncated" : full,
                attempt,
                Instant.now());
    }

    /** A failure with no exception behind it — a lease sweep, an operator. */
    public static TaskFailure of(String message, int attempt) {
        return new TaskFailure(null, message, null, attempt, Instant.now());
    }
}
