package ai.mindconnect.taskqueue.schedule;

import ai.mindconnect.taskqueue.TaskQueue;
import ai.mindconnect.taskqueue.TaskSubmission;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Turns schedules into tasks. It owns no execution, no threads for work and no
 * state of its own: every tick it asks which schedules are due, claims each
 * firing exactly once and hands the result to the {@link TaskQueue} as an
 * ordinary submission. Everything after that — priority, retry, cancel,
 * failure recording — is the queue's, unchanged.
 *
 * <p>Safe to run on every node. Two schedulers ticking in the same second
 * both compute the same due firing and both call
 * {@link ScheduleStore#claimFiring}; one wins, the other moves on. There is no
 * leader, no lock and nothing to release when a node dies mid-tick.
 *
 * <pre>{@code
 * var schedules = new InMemoryScheduleStore();
 * try (var scheduler = new TaskScheduler(queue, schedules)) {
 *     scheduler.schedule(TaskSchedule.of("nightly-report", "report", "0 3 * * *", Map.of())
 *             .in(ZoneId.of("Europe/Zurich"))
 *             .withMaxAttempts(3));
 *     scheduler.start();
 * }
 * }</pre>
 */
public final class TaskScheduler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TaskScheduler.class);

    /**
     * How far a single tick will walk forward to find the newest due firing.
     * Reached only after a long outage; the next tick continues from there.
     */
    private static final int MAX_CATCH_UP_STEPS = 10_000;

    private final TaskQueue queue;
    private final ScheduleStore store;
    private final Clock clock;
    private final Object wakeup = new Object();

    private volatile Duration maxSleep = Duration.ofSeconds(30);
    private volatile Thread ticker;
    private volatile boolean running;

    public TaskScheduler(TaskQueue queue, ScheduleStore store) {
        this(queue, store, Clock.systemUTC());
    }

    /** A steerable clock makes a year of firings testable in a millisecond. */
    public TaskScheduler(TaskQueue queue, ScheduleStore store, Clock clock) {
        this.queue = queue;
        this.store = store;
        this.clock = clock;
    }

    /**
     * The longest the loop sleeps even when nothing is due — the window in
     * which a schedule another node added or edited is picked up.
     */
    public TaskScheduler withMaxSleep(Duration maxSleep) {
        this.maxSleep = maxSleep;
        signal();
        return this;
    }

    /** Stores the schedule, stamping the baseline so it cannot fire for the past. */
    public TaskSchedule schedule(TaskSchedule schedule) {
        TaskSchedule stamped = schedule.startingAt(clock.instant());
        store.save(stamped);
        signal();
        return stamped;
    }

    public boolean unschedule(String scheduleId) {
        boolean removed = store.delete(scheduleId);
        signal();
        return removed;
    }

    public Optional<TaskSchedule> get(String scheduleId) {
        return store.find(scheduleId);
    }

    /** Starts the background loop; idempotent. */
    public synchronized void start() {
        if (running) return;
        running = true;
        ticker = Thread.ofPlatform().name("task-scheduler").daemon(true).start(this::loop);
    }

    @Override
    public synchronized void close() {
        running = false;
        if (ticker != null) ticker.interrupt();
    }

    /**
     * One pass over every schedule — the whole scheduler, without a thread.
     * Public because it is also the honest way to test one: no sleeping, no
     * waiting, one call per point in (simulated) time.
     *
     * @return how many tasks this pass submitted
     */
    public int tick() {
        Instant now = clock.instant();
        int submitted = 0;
        for (TaskSchedule schedule : store.all()) {
            try {
                if (fire(schedule, now)) submitted++;
            } catch (RuntimeException e) {
                // One broken schedule must not stop the other nineteen.
                log.error("Schedule {} failed to fire: {}", schedule.id(), e.toString(), e);
            }
        }
        return submitted;
    }

    /** When the earliest schedule fires next — what the loop sleeps until. */
    public Optional<Instant> nextFireAt() {
        Instant now = clock.instant();
        Instant earliest = null;
        for (TaskSchedule schedule : store.all()) {
            if (!schedule.enabled() || schedule.baseline() == null) continue;
            Instant next = nextFiring(schedule, schedule.baseline());
            if (next == null) continue;
            if (next.isBefore(now)) return Optional.of(now);          // overdue: do not sleep
            if (earliest == null || next.isBefore(earliest)) earliest = next;
        }
        return Optional.ofNullable(earliest);
    }

    // ── firing ──────────────────────────────────────────────────────────────

    private boolean fire(TaskSchedule schedule, Instant now) {
        if (!schedule.enabled() || schedule.baseline() == null) return false;
        Instant due = newestDueFiring(schedule, now);
        if (due == null) return false;

        // The compare-and-set: whoever writes this firing time owns it.
        Optional<TaskSchedule> claimed = store.claimFiring(schedule.id(), due);
        if (claimed.isEmpty()) return false;

        String taskId = queue.submit(TaskSubmission.of(schedule.taskType(), schedule.payload())
                .withPriority(schedule.priority())
                .withMaxAttempts(schedule.maxAttempts())
                .at(due));                       // the scheduled instant, not "now"
        store.recordFired(schedule.id(), taskId);
        log.debug("Schedule {} fired for {} → task {}", schedule.id(), due, taskId);
        return true;
    }

    /**
     * The newest firing that is already due, or null when none is.
     *
     * <p>Missed firings collapse into one: a node that was down for an hour
     * runs an hourly job once, not sixty times. Every skipped firing is still
     * passed over by the claim, so nobody else runs them either.
     */
    private Instant newestDueFiring(TaskSchedule schedule, Instant now) {
        Instant next = nextFiring(schedule, schedule.baseline());
        if (next == null || next.isAfter(now)) return null;
        Instant newest = next;
        for (int step = 0; step < MAX_CATCH_UP_STEPS; step++) {
            Instant following = nextFiring(schedule, newest);
            if (following == null || following.isAfter(now)) return newest;
            newest = following;
        }
        log.warn("Schedule {} is more than {} firings behind — catching up over several ticks",
                schedule.id(), MAX_CATCH_UP_STEPS);
        return newest;
    }

    private Instant nextFiring(TaskSchedule schedule, Instant from) {
        return schedule.nextFireAfter(from).orElse(null);
    }

    // ── loop ────────────────────────────────────────────────────────────────

    private void loop() {
        while (running) {
            try {
                tick();
                synchronized (wakeup) {
                    wakeup.wait(sleepMillis());
                }
            } catch (InterruptedException e) {
                return;                                   // close()
            } catch (RuntimeException e) {
                log.error("Scheduler tick failed, continuing", e);
            }
        }
    }

    /** Sleep until the next firing, but never past the window for picking up edits. */
    private long sleepMillis() {
        long cap = maxSleep.toMillis();
        Optional<Instant> next = nextFireAt();
        if (next.isEmpty()) return cap;
        long untilNext = Duration.between(clock.instant(), next.get()).toMillis();
        return Math.max(50, Math.min(cap, untilNext));
    }

    private void signal() {
        synchronized (wakeup) {
            wakeup.notifyAll();
        }
    }
}
