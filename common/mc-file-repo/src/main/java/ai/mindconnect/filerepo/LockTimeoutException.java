package ai.mindconnect.filerepo;

import java.nio.file.Path;
import java.time.Duration;

/**
 * The write lock on a file stayed taken longer than the timeout. A write holds
 * its lock for one small file, so this means something hangs while holding it;
 * the message names the thread that did.
 */
public class LockTimeoutException extends FileRepoException {

    public LockTimeoutException(Path file, Duration timeout, String holder) {
        super("No write lock on " + file + " within " + timeout + " — held by " + holder);
    }
}
