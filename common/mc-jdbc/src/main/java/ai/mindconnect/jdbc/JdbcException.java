package ai.mindconnect.jdbc;

/** The one unchecked exception this library throws; the SQLException is always the cause. */
public class JdbcException extends RuntimeException {

    public JdbcException(String message) {
        super(message);
    }

    public JdbcException(String message, Throwable cause) {
        super(message, cause);
    }
}
