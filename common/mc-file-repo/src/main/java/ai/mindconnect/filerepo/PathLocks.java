package ai.mindconnect.filerepo;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Write locks by file path, shared by the whole JVM.
 *
 * <p>The lock belongs to the path, not to whoever writes it: two store
 * instances on the same directory — built by two runtimes, or opened afresh
 * by a tool — serialize against each other without knowing of each other.
 * Paths map onto a fixed set of lock stripes, so nothing grows and nothing
 * needs cleaning up; two paths sharing a stripe merely wait for each other
 * for the length of one small write.
 *
 * <p>Three rules keep this free of deadlocks and hangs:
 * <ul>
 *   <li><b>One write lock per thread.</b> Asking for a second one while
 *       holding the first throws {@link NestedWriteException}. No thread
 *       ever waits while holding a lock, so no two threads can wait for
 *       each other.</li>
 *   <li><b>Waiting has a limit.</b> A lock still taken after the timeout
 *       throws {@link LockTimeoutException} naming the holder, instead of
 *       parking the caller forever.</li>
 *   <li><b>Fair order.</b> Waiting writers get the lock in arrival order, so
 *       under a burst no writer times out while later ones keep cutting in.</li>
 * </ul>
 *
 * <p>Readers take no lock at all; {@link FileWrites} replaces a file in one
 * step, so a reader always holds a whole version. {@code ReentrantLock} rather
 * than {@code synchronized} lets a waiting virtual thread unmount from its carrier.
 */
public class PathLocks {

    /** The action run under the lock. */
    @FunctionalInterface
    public interface Action<T> {
        T run() throws IOException;
    }

    /** How long a writer waits by default before {@link LockTimeoutException}. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    static final int STRIPES = 4096;

    private static final OwnedLock[] LOCKS = new OwnedLock[STRIPES];

    static {
        for (int i = 0; i < STRIPES; i++) {
            LOCKS[i] = new OwnedLock();
        }
    }

    private static final ThreadLocal<Path> HELD = new ThreadLocal<>();

    private PathLocks() {
    }

    /**
     * Runs {@code action} holding the write lock of {@code file}.
     *
     * @throws NestedWriteException  when this thread already holds a write lock
     * @throws LockTimeoutException  when the lock stays taken for {@code timeout}
     */
    public static <T> T withLock(Path file, Duration timeout, Action<T> action) throws IOException {
        Path key = file.toAbsolutePath().normalize();
        Path held = HELD.get();
        if (held != null) {
            throw new NestedWriteException(key, held);
        }
        OwnedLock lock = LOCKS[stripeOf(key)];
        boolean acquired;
        try {
            acquired = lock.tryLock(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FileRepoException("Interrupted while waiting for the write lock on " + key, e);
        }
        if (!acquired) {
            Thread owner = lock.owner();
            throw new LockTimeoutException(key, timeout, owner == null ? "nobody any more" : owner.toString());
        }
        HELD.set(key);
        try {
            return action.run();
        } finally {
            HELD.remove();
            lock.unlock();
        }
    }

    /** Whether the current thread is inside {@link #withLock}. */
    public static boolean isHeldByCurrentThread() {
        return HELD.get() != null;
    }

    static int stripeOf(Path file) {
        return Math.floorMod(file.toAbsolutePath().normalize().hashCode(), STRIPES);
    }

    /** A fair lock that tells who holds it. */
    private static class OwnedLock extends ReentrantLock {

        OwnedLock() {
            super(true);
        }

        Thread owner() {
            return getOwner();
        }
    }
}
