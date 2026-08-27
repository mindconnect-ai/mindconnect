package ai.mindconnect.taskqueue.schedule.memory;

import ai.mindconnect.taskqueue.schedule.ScheduleStore;
import ai.mindconnect.taskqueue.schedule.TaskSchedule;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reference store: a synchronized map. The compare-and-set that keeps two
 * nodes from firing the same schedule twice comes from the monitor here and
 * from a conditional {@code UPDATE} in a database store — same rule, same
 * outcome, different mechanism.
 */
public final class InMemoryScheduleStore implements ScheduleStore {

    private final Map<String, TaskSchedule> schedules = new LinkedHashMap<>();

    @Override
    public synchronized void save(TaskSchedule schedule) {
        schedules.put(schedule.id(), schedule);
    }

    @Override
    public synchronized Optional<TaskSchedule> find(String id) {
        return Optional.ofNullable(schedules.get(id));
    }

    @Override
    public synchronized List<TaskSchedule> all() {
        return new ArrayList<>(schedules.values());
    }

    @Override
    public synchronized boolean delete(String id) {
        return schedules.remove(id) != null;
    }

    @Override
    public synchronized Optional<TaskSchedule> claimFiring(String id, Instant firedFor) {
        TaskSchedule schedule = schedules.get(id);
        if (schedule == null) return Optional.empty();
        Instant claimed = schedule.lastFiredFor();
        // Only strictly newer firings are claimable — a repeated tick, a
        // second node and a restart all lose against the same check.
        if (claimed != null && !claimed.isBefore(firedFor)) return Optional.empty();
        TaskSchedule updated = schedule.firedFor(firedFor);
        schedules.put(id, updated);
        return Optional.of(updated);
    }

    @Override
    public synchronized void recordFired(String id, String taskId) {
        TaskSchedule schedule = schedules.get(id);
        if (schedule != null) schedules.put(id, schedule.withLastTaskId(taskId));
    }
}
