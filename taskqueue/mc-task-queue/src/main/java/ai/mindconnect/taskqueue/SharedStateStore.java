package ai.mindconnect.taskqueue;

import java.util.Map;
import java.util.Optional;

/**
 * State that outlives a single task: what several tasks working on the same
 * piece of work need to agree on. Addressed by an {@code id} — the piece of
 * work, by convention the id of the task that started it — and a {@code key}
 * within it.
 *
 * <p>Distinct from {@link TaskRecord#state()}, which belongs to ONE task and
 * travels with its record. This is the shared one, and it earns its place as
 * a store because the alternative is a coordinator TASK that owns the map and
 * that everyone else has to report to — a second task type, and a round trip
 * through the mailbox, for what is a map.
 *
 * <p>{@link #putIfAbsent} is why this is a port and not a {@code Map}: tasks
 * run concurrently, so "look whether it is there, then write it" is a race
 * two workers lose together. The claim has to be ONE step — in memory a
 * {@code ConcurrentHashMap}, in Postgres an
 * {@code INSERT … ON CONFLICT DO NOTHING}. Values are data only, like
 * payloads, and never null.
 */
public interface SharedStateStore {

    /**
     * Claims {@code key} under {@code id}: writes {@code value} and answers
     * {@code true} only if nothing was there before. The single caller that
     * gets {@code true} owns whatever the key stands for — the work it names
     * is theirs, and everyone else skips it.
     */
    boolean putIfAbsent(String id, String key, Object value);

    /** Writes over whatever was there. */
    void put(String id, String key, Object value);

    Optional<Object> get(String id, String key);

    /** A snapshot of everything under {@code id}. */
    Map<String, Object> all(String id);

    /**
     * Forgets everything under {@code id} and says how many entries went — for
     * the task that knows the work is over. Nothing expires on its own: the
     * store cannot know whether a crawl is finished or merely paused.
     */
    int clear(String id);
}
