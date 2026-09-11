package ai.mindconnect.filerepo;

/** The unchecked exception this library throws; where an {@code IOException} caused it, that is the cause. */
public class FileRepoException extends RuntimeException {

    public FileRepoException(String message) {
        super(message);
    }

    public FileRepoException(String message, Throwable cause) {
        super(message, cause);
    }
}
