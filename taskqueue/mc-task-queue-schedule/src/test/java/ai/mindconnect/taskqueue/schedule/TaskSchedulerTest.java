package ai.mindconnect.taskqueue.schedule;

import ai.mindconnect.taskqueue.TaskOutcome;
import ai.mindconnect.taskqueue.TaskRecord;
import ai.mindconnect.taskqueue.TaskStatus;
import ai.mindconnect.taskqueue.local.LocalTaskQueue;
import ai.mindconnect.taskqueue.memory.InMemoryTaskStore;
import ai.mindconnect.taskqueue.schedule.memory.InMemoryScheduleStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scheduler, steered by hand: {@code tick()} is the whole loop body, so a
 * year of firings takes no waiting at all.
 */
class TaskSchedulerTest {

    private static final ZoneId UTC = ZoneOffset.UTC;

    private static final class TestClock extends Clock {
        private volatile Instant now = Instant.parse("2026-01-01T12:00:00Z");
        @Override public ZoneId getZone() { return UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void set(String isoUtc) { now = Instant.parse(isoUtc); }
        void advance(Duration by) { now = now.plus(by); }
    }

    private TestClock clock;
    private InMemoryTaskStore tasks;
    private InMemoryScheduleStore schedules;
    private LocalTaskQueue queue;
    private TaskScheduler scheduler;

    @BeforeEach
    void setUp() {
        clock = new TestClock();
        tasks = new InMemoryTaskStore(clock);
        schedules = new InMemoryScheduleStore();
        queue = new LocalTaskQueue(tasks);
        scheduler = new TaskScheduler(queue, schedules, clock);
    }

    @AfterEach
    void tearDown() {
        scheduler.close();
        queue.close();
    }

    private TaskSchedule daily(String id) {
        return scheduler.schedule(
                TaskSchedule.of(id, "report", "0 3 * * *", Map.of("scope", "all")).in(UTC));
    }

    @Test
    void nothingFiresBeforeItsTime() {
        daily("nightly");
        assertThat(scheduler.tick()).isZero();
        clock.set("2026-01-02T02:59:59Z");
        assertThat(scheduler.tick()).isZero();
    }

    @Test
    void aDueScheduleSubmitsExactlyOneTask() {
        daily("nightly");
        clock.set("2026-01-02T03:00:00Z");

        assertThat(scheduler.tick()).isEqualTo(1);
        List<TaskRecord> submitted = tasks.byStatus(TaskStatus.QUEUED, 10);
        assertThat(submitted).hasSize(1);
        assertThat(submitted.get(0).type()).isEqualTo("report");
        assertThat(submitted.get(0).payload()).containsEntry("scope", "all");
        // The task carries the instant it was scheduled FOR, not the moment
        // the tick happened to notice.
        assertThat(submitted.get(0).runAfter()).isEqualTo(Instant.parse("2026-01-02T03:00:00Z"));
    }

    @Test
    void aSecondTickAtTheSameTimeFiresNothing() {
        daily("nightly");
        clock.set("2026-01-02T03:00:00Z");

        assertThat(scheduler.tick()).isEqualTo(1);
        assertThat(scheduler.tick()).isZero();
        assertThat(scheduler.tick()).isZero();
        assertThat(tasks.byStatus(TaskStatus.QUEUED, 10)).hasSize(1);
    }

    @Test
    void twoNodesOnOneStoreProduceOneTask() {
        // The point of the compare-and-set: no leader, no lock, and both
        // schedulers reaching the same conclusion is harmless.
        TaskScheduler otherNode = new TaskScheduler(queue, schedules, clock);
        daily("nightly");
        clock.set("2026-01-02T03:00:00Z");

        int firstNode = scheduler.tick();
        int secondNode = otherNode.tick();

        assertThat(firstNode + secondNode).isEqualTo(1);
        assertThat(tasks.byStatus(TaskStatus.QUEUED, 10)).hasSize(1);
        otherNode.close();
    }

    @Test
    void missedFiringsCollapseIntoASingleCatchUpRun() {
        scheduler.schedule(TaskSchedule.of("hourly", "sweep", "0 * * * *", Map.of()).in(UTC));

        clock.advance(Duration.ofHours(5));                 // the node was down for five hours
        assertThat(scheduler.tick()).isEqualTo(1);          // one run, not five

        assertThat(tasks.byStatus(TaskStatus.QUEUED, 10)).hasSize(1);
        // Everything that was skipped is still marked as dealt with, so no
        // other node picks the missed firings up either.
        assertThat(schedules.find("hourly").orElseThrow().lastFiredFor())
                .isEqualTo(Instant.parse("2026-01-01T17:00:00Z"));
    }

    @Test
    void aNewScheduleNeverFiresForATimeBeforeItExisted() {
        clock.set("2026-01-01T03:30:00Z");                  // just after today's 03:00
        daily("nightly");

        assertThat(scheduler.tick()).isZero();
        clock.set("2026-01-02T03:00:00Z");
        assertThat(scheduler.tick()).isEqualTo(1);
    }

    @Test
    void aDisabledScheduleFiresNothingAndDoesNotPileUp() {
        TaskSchedule paused = daily("nightly").enabled(false);
        schedules.save(paused);

        clock.set("2026-01-05T03:00:00Z");                  // three firings passed
        assertThat(scheduler.tick()).isZero();

        schedules.save(schedules.find("nightly").orElseThrow().enabled(true));
        assertThat(scheduler.tick()).isEqualTo(1);          // one catch-up run, not three
    }

    @Test
    void priorityAndMaxAttemptsRideAlongToEveryTask() {
        scheduler.schedule(TaskSchedule.of("nightly", "report", "0 3 * * *", Map.of())
                .in(UTC).withPriority(7).withMaxAttempts(3));
        clock.set("2026-01-02T03:00:00Z");
        scheduler.tick();

        TaskRecord task = tasks.byStatus(TaskStatus.QUEUED, 1).get(0);
        assertThat(task.priority()).isEqualTo(7);
        assertThat(task.maxAttempts()).isEqualTo(3);
    }

    @Test
    void theScheduleRemembersWhichTaskItProduced() {
        daily("nightly");
        clock.set("2026-01-02T03:00:00Z");
        scheduler.tick();

        String taskId = tasks.byStatus(TaskStatus.QUEUED, 1).get(0).id();
        assertThat(schedules.find("nightly").orElseThrow().lastTaskId()).isEqualTo(taskId);
    }

    @Test
    void unscheduleStopsIt() {
        daily("nightly");
        assertThat(scheduler.unschedule("nightly")).isTrue();
        clock.set("2026-01-02T03:00:00Z");
        assertThat(scheduler.tick()).isZero();
    }

    @Test
    void nextFireAtIsWhatTheLoopSleepsUntil() {
        daily("nightly");
        assertThat(scheduler.nextFireAt()).contains(Instant.parse("2026-01-02T03:00:00Z"));

        clock.set("2026-01-02T03:00:01Z");                  // overdue → do not sleep at all
        assertThat(scheduler.nextFireAt()).contains(clock.instant());
    }

    @Test
    void aFiredTaskRunsThroughTheQueueLikeAnyOther() {
        AtomicInteger runs = new AtomicInteger();
        queue.register("report", ctx -> {
            runs.incrementAndGet();
            return TaskOutcome.done("report for " + ctx.task().payload().get("scope"));
        });
        daily("nightly");
        clock.set("2026-01-02T03:00:00Z");
        scheduler.tick();

        String taskId = schedules.find("nightly").orElseThrow().lastTaskId();
        TaskRecord done = queue.await(taskId, Duration.ofSeconds(5));
        assertThat(done.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(done.result()).isEqualTo("report for all");
        assertThat(runs).hasValue(1);
    }

    @Test
    void aBrokenScheduleDoesNotStopTheOthers() {
        // A cron that parses but can never match: the scheduler must shrug.
        scheduler.schedule(TaskSchedule.of("impossible", "never", "0 0 30 2 *", Map.of()).in(UTC));
        daily("nightly");
        clock.set("2026-01-02T03:00:00Z");

        assertThat(scheduler.tick()).isEqualTo(1);
    }
}
