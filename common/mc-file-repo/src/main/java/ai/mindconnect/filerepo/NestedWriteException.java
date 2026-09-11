package ai.mindconnect.filerepo;

import java.nio.file.Path;

/**
 * A thread asked for a write lock while it already held one — typically a
 * store written from inside an {@link Documents#update update} function.
 *
 * <p>This is a programming error, not a condition to retry: two threads that
 * each hold one lock and wait for the other's are a deadlock, and refusing the
 * second lock outright is what makes that impossible. Move the second write
 * after the first one returns.
 */
public class NestedWriteException extends FileRepoException {

    public NestedWriteException(Path requested, Path held) {
        super("Write to " + requested + " requested while this thread holds the write lock on " + held
                + " — write it after the first write has returned");
    }
}
