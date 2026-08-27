package ai.mindconnect.taskqueue.schedule;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;

/**
 * A recurring reason to submit a task — the definition, not the task. It says
 * WHAT to submit and WHEN it recurs; every firing produces an ordinary
 * {@code TaskSubmission} that the queue handles like any other.
 *
 * <p>That separation is the whole design. A schedule is long-lived, editable
 * and outlives every run it starts, so it is not a task with a
 * {@code runAfter} that re-submits itself: it is its own record, in its own
 * store, and a queue that knows nothing about cron keeps working exactly as
 * before.
 *
 * @param payload      what every firing submits — data only, same rule as a task payload
 * @param zone         the zone the cron fields are read in. "03:00" is a local
 *                     statement; without a zone it is only a statement about UTC
 * @param maxAttempts  handed to every submitted task, so a flaky nightly job
 *                     retries without anyone watching
 * @param createdAt    the baseline for the FIRST firing — a schedule never
 *                     fires for a time before it existed. Stamped when it is
 *                     handed to the scheduler
 * @param lastFiredFor the firing time this schedule has already been claimed
 *                     for. The compare-and-set field: it is what makes two
 *                     nodes ticking at the same second produce one task, not two
 * @param lastTaskId   the task the last firing produced — the link from
 *                     schedule to run, for a dispatcher UI
 */
public record TaskSchedule(
        String id,
        String name,
        String taskType,
        Map<String, Object> payload,
        String cron,
        ZoneId zone,
        boolean enabled,
        int priority,
        int maxAttempts,
        Instant createdAt,
        Instant lastFiredFor,
        String lastTaskId
) {

    /**
     * The cron is parsed right here so a broken expression fails at the caller
     * rather than becoming a schedule that silently never fires.
     */
    public static TaskSchedule of(String id, String taskType, String cron, Map<String, Object> payload) {
        CronExpression.parse(cron);
        return new TaskSchedule(id, id, taskType, Map.copyOf(payload), cron,
                ZoneId.systemDefault(), true, 0, 1, null, null, null);
    }

    public TaskSchedule named(String name) {
        return new TaskSchedule(id, name, taskType, payload, cron, zone, enabled, priority,
                maxAttempts, createdAt, lastFiredFor, lastTaskId);
    }

    /** The zone the cron fields mean — {@code Europe/Zurich} for "03:00 our time". */
    public TaskSchedule in(ZoneId zone) {
        return new TaskSchedule(id, name, taskType, payload, cron, zone, enabled, priority,
                maxAttempts, createdAt, lastFiredFor, lastTaskId);
    }

    /** Paused: kept, editable, and firing nothing. Missed firings do not pile up. */
    public TaskSchedule enabled(boolean enabled) {
        return new TaskSchedule(id, name, taskType, payload, cron, zone, enabled, priority,
                maxAttempts, createdAt, lastFiredFor, lastTaskId);
    }

    public TaskSchedule withPriority(int priority) {
        return new TaskSchedule(id, name, taskType, payload, cron, zone, enabled, priority,
                maxAttempts, createdAt, lastFiredFor, lastTaskId);
    }

    public TaskSchedule withMaxAttempts(int maxAttempts) {
        return new TaskSchedule(id, name, taskType, payload, cron, zone, enabled, priority,
                maxAttempts, createdAt, lastFiredFor, lastTaskId);
    }

    /** Sets the baseline for the first firing; leaves an existing one alone. */
    public TaskSchedule startingAt(Instant createdAt) {
        return this.createdAt != null ? this
                : new TaskSchedule(id, name, taskType, payload, cron, zone, enabled, priority,
                maxAttempts, createdAt, lastFiredFor, lastTaskId);
    }

    /** Claimed for {@code firedFor} — the transition a store's compare-and-set writes. */
    public TaskSchedule firedFor(Instant firedFor) {
        return new TaskSchedule(id, name, taskType, payload, cron, zone, enabled, priority,
                maxAttempts, createdAt, firedFor, lastTaskId);
    }

    public TaskSchedule withLastTaskId(String lastTaskId) {
        return new TaskSchedule(id, name, taskType, payload, cron, zone, enabled, priority,
                maxAttempts, createdAt, lastFiredFor, lastTaskId);
    }

    public CronExpression expression() {
        return CronExpression.parse(cron);
    }

    /**
     * Where the search for the next firing starts: the last claimed firing, or
     * the moment the schedule was created. Never {@code null} once the
     * scheduler has stamped it.
     */
    public Instant baseline() {
        return lastFiredFor != null ? lastFiredFor : createdAt;
    }

    /** When this schedule fires next, purely from its own definition. */
    public Optional<Instant> nextFireAfter(Instant from) {
        return expression().nextAfter(from, zone);
    }
}
