package ai.mindconnect.taskqueue;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Mutual exclusion between tasks that do NOT share a process: one node writes
 * to a directory, calls an API that tolerates a single caller, or runs a job
 * that must not run twice — and the others wait or move on.
 *
 * <p>The same reasoning as the task leases in {@link TaskStore}: a holder can
 * die mid-work, and a store cannot tell a live holder from a dead one. So a
 * lock is never held outright, only for a {@code lease} — long work keeps it
 * by {@link #renew renewing}, and a holder that stops renewing loses it. That
 * is what makes the lock survive a node that never comes back, and it is why
 * a lease is not optional here.
 *
 * <p>{@link #acquire} answers with a TOKEN rather than a boolean, and
 * renew/release demand it back. Without one, a holder whose lease expired
 * while it was paused would happily release the lock a second holder now
 * owns — the classic way a distributed lock hands the same work to two nodes.
 * The token does not stop a zombie from writing to the protected resource; if
 * that matters, the resource itself has to reject stale writers, which needs
 * a fencing number this port could hand out later.
 *
 * <p>Acquiring never blocks: it either gets the lock now or does not. Waiting
 * is a policy for the caller — retry, {@code runAfter} a delay, or give up —
 * and a queue whose workers park instead of blocking should not grow a
 * blocking primitive at its center.
 */
public interface SharedLockStore {

    /**
     * Takes the lock on {@code key} under {@code id} for {@code lease}, if it
     * is free — or held by nobody any more, an expired lease counting as free.
     *
     * @return the token identifying this holder, empty when someone else holds it
     */
    Optional<String> acquire(String id, String key, Duration lease);

    /**
     * "Still working": extends the lease by {@code lease} from now.
     *
     * @return false when the lock is gone — expired, or already taken over.
     *         The caller must stop treating itself as the holder
     */
    boolean renew(String id, String key, String token, Duration lease);

    /**
     * Hands the lock back early instead of waiting out the lease.
     *
     * @return false when {@code token} was not the current holder — someone
     *         else's lock stays untouched
     */
    boolean release(String id, String key, String token);

    /** When the current lease runs out, empty when the lock is free — for an ops view. */
    Optional<Instant> heldUntil(String id, String key);
}
