package ai.mindconnect.taskqueue.memory;

import ai.mindconnect.taskqueue.TaskFailure;
import ai.mindconnect.taskqueue.TaskNotification;
import ai.mindconnect.taskqueue.TaskOutcome;
import ai.mindconnect.taskqueue.TaskRecord;
import ai.mindconnect.taskqueue.TaskStatus;
import ai.mindconnect.taskqueue.TaskStore;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Reference store: a synchronized map. Atomicity of claim/cancel comes from
 * the monitor — a database store gets the same guarantees from
 * {@code SELECT … FOR UPDATE SKIP LOCKED}. Insertion order of the map is the
 * FIFO tie-breaker within a priority.
 */
public final class InMemoryTaskStore implements TaskStore {

    private final Map<String, TaskRecord> tasks = new LinkedHashMap<>();
    private final Clock clock;
    private final String nodeId;

    public InMemoryTaskStore() {
        this(Clock.systemUTC());
    }

    /** A fixed or steerable clock makes delays and retries testable without waiting. */
    public InMemoryTaskStore(Clock clock) {
        this(clock, "local");
    }

    /** @param nodeId stamped onto every claim — one process, one name. */
    public InMemoryTaskStore(Clock clock, String nodeId) {
        this.clock = clock;
        this.nodeId = nodeId;
    }

    @Override
    public synchronized void save(TaskRecord record) {
        TaskRecord existing = tasks.get(record.id());
        tasks.put(record.id(), mergedSave(existing, record));
    }

    /**
     * A save is built from a snapshot, and two things may have landed since
     * it was taken: mail and a cancel request. Neither may be erased by an
     * older picture — the ROW's mailbox wins (only {@code drainNotifications}
     * empties it) and the cancel flag is sticky.
     */
    static TaskRecord mergedSave(TaskRecord existing, TaskRecord record) {
        if (existing == null) return record;
        TaskRecord merged = record;
        if (!existing.notifications().isEmpty() && record.notifications().isEmpty()) {
            for (TaskNotification note : existing.notifications()) {
                merged = merged.withNotification(note);
            }
        }
        if (existing.cancelRequested() && !record.cancelRequested()) {
            merged = merged.withCancelRequested();
        }
        return merged;
    }

    @Override
    public synchronized Optional<TaskRecord> insertIfAbsent(TaskRecord record) {
        TaskRecord existing = tasks.putIfAbsent(record.id(), record);
        return Optional.ofNullable(existing);
    }

    @Override
    public synchronized Optional<TaskRecord> find(String id) {
        return Optional.ofNullable(tasks.get(id));
    }

    @Override
    public synchronized List<TaskRecord> byStatus(TaskStatus status, int limit) {
        List<TaskRecord> result = new ArrayList<>();
        for (TaskRecord record : tasks.values()) {
            if (record.status() == status) {
                result.add(record);
                if (result.size() >= limit) break;
            }
        }
        return result;
    }

    @Override
    public synchronized int purgeTerminal(java.time.Instant before) {
        Map<String, List<String>> childIds = new LinkedHashMap<>();
        List<String> roots = new ArrayList<>();
        for (TaskRecord record : tasks.values()) {
            String parent = record.parentTaskId();
            // A task whose parent is already gone is a root itself — otherwise
            // it would be unreachable and could never be purged.
            if (parent == null || !tasks.containsKey(parent)) {
                roots.add(record.id());
            } else {
                childIds.computeIfAbsent(parent, key -> new ArrayList<>()).add(record.id());
            }
        }
        int removed = 0;
        for (String root : roots) {
            List<String> tree = new ArrayList<>();
            collectTree(root, childIds, tree);
            if (purgeable(tree, before)) {
                tree.forEach(tasks::remove);
                removed += tree.size();
            }
        }
        return removed;
    }

    private void collectTree(String id, Map<String, List<String>> childIds, List<String> into) {
        into.add(id);
        for (String child : childIds.getOrDefault(id, List.of())) {
            collectTree(child, childIds, into);
        }
    }

    /** A tree goes only if it is finished as a whole and old enough as a whole. */
    private boolean purgeable(List<String> tree, java.time.Instant before) {
        for (String id : tree) {
            TaskRecord record = tasks.get(id);
            if (!record.status().terminal()) return false;
            if (record.endedAt() == null || !record.endedAt().isBefore(before)) return false;
        }
        return true;
    }

    @Override
    public synchronized Optional<TaskRecord> claimNext(Set<String> types) {
        TaskRecord best = null;
        for (TaskRecord record : tasks.values()) {          // insertion order = FIFO tie-breaker
            if (record.status() != TaskStatus.QUEUED || !types.contains(record.type())) continue;
            if (!record.isDue(clock.instant())) continue;   // not yet due — stays queued
            if (best == null || record.priority() > best.priority()) {
                best = record;                              // strictly greater — first of equals wins
            }
        }
        if (best == null) return Optional.empty();
        TaskRecord claimed = best.claimed(nodeId);
        tasks.put(claimed.id(), claimed);
        return Optional.of(claimed);
    }

    @Override
    public synchronized Optional<TaskRecord> requestCancel(String id) {
        TaskRecord record = tasks.get(id);
        if (record == null || record.status().terminal()) return Optional.empty();
        // nothing is running for QUEUED and WAITING tasks — cancel outright
        TaskRecord updated = record.status() == TaskStatus.RUNNING
                ? record.withCancelRequested()
                : record.cancelled();
        tasks.put(id, updated);
        return Optional.of(updated);
    }

    @Override
    public synchronized TaskRecord suspend(String id, TaskOutcome.Suspend suspend) {
        Set<String> waitingFor = new HashSet<>(suspend.awaitedTaskIds());
        if (suspend.awaitChildren()) {
            // Resolved under the store lock, so a child submitted moments ago
            // is part of the wait rather than a missed wakeup.
            for (TaskRecord child : tasks.values()) {
                if (id.equals(child.parentTaskId())) waitingFor.add(child.id());
            }
        }
        Set<String> open = new HashSet<>();
        for (String awaited : waitingFor) {
            TaskRecord child = tasks.get(awaited);
            if (child == null || !child.status().terminal()) open.add(awaited);
        }
        TaskRecord record = tasks.get(id);
        // A cancel that arrived while we were running must not be slept
        // through: a task asked to die parks nowhere — it dies here.
        if (record.cancelRequested()) {
            TaskRecord cancelled = record.cancelled();
            tasks.put(id, cancelled);
            return cancelled;
        }
        // The park decision is the record's, not the store's — see
        // TaskRecord#parkedOn; a store only resolves WHAT is still open.
        boolean notificationWait = suspend.awaitedTaskIds().isEmpty() && !suspend.awaitChildren();
        TaskRecord updated = record.parkedOn(waitingFor, open, notificationWait);
        tasks.put(id, updated);
        return updated;
    }

    @Override
    public synchronized Optional<TaskRecord> notify(String id, TaskNotification notification) {
        TaskRecord record = tasks.get(id);
        if (record == null || record.status().terminal()) return Optional.empty();
        TaskRecord updated = record.withNotification(notification);
        boolean wakes = updated.status() == TaskStatus.SUSPENDED;   // a message always wakes
        if (wakes) updated = updated.requeued();
        tasks.put(id, updated);
        return wakes ? Optional.of(updated) : Optional.empty();
    }

    @Override
    public synchronized List<TaskNotification> drainNotifications(String id) {
        TaskRecord record = tasks.get(id);
        if (record == null || record.notifications().isEmpty()) return List.of();
        tasks.put(id, record.withDrainedMailbox());
        return record.notifications();
    }

    @Override
    public synchronized void updateState(String id, Map<String, Object> state) {
        TaskRecord record = tasks.get(id);
        if (record != null) tasks.put(id, record.withState(state));
    }

    @Override
    public synchronized List<TaskRecord> finish(TaskRecord terminal) {
        TaskRecord existing = tasks.get(terminal.id());
        tasks.put(terminal.id(), mergedSave(existing, terminal));
        return wake(terminal.id());
    }

    /** Internal since {@code finish}: waking is part of turning terminal. */
    private List<TaskRecord> wake(String terminalTaskId) {
        List<TaskRecord> requeued = new ArrayList<>();
        for (Map.Entry<String, TaskRecord> entry : tasks.entrySet()) {
            TaskRecord record = entry.getValue();
            if (!record.waitingFor().contains(terminalTaskId)) {
                continue;
            }
            TaskRecord updated = record.notWaitingFor(terminalTaskId);
            if (record.status() != TaskStatus.SUSPENDED) {
                // Not parked (woken by a message, or already running): only
                // keep the ledger honest, the wake decision is not ours here.
                updated = record.withWaitingFor(updated.waitingFor());
            }
            entry.setValue(updated);
            if (record.status() == TaskStatus.SUSPENDED
                    && updated.status() == TaskStatus.QUEUED) requeued.add(updated);
        }
        return requeued;
    }

    @Override
    public synchronized List<TaskRecord> byParent(String id) {
        List<TaskRecord> children = new ArrayList<>();
        for (TaskRecord record : tasks.values()) {
            if (id.equals(record.parentTaskId())) children.add(record);
        }
        return children;
    }

    @Override
    public synchronized TaskRecord retry(String id, java.time.Duration delay, TaskFailure failure) {
        TaskRecord record = tasks.get(id);
        TaskRecord updated = record.retryAt(clock.instant().plus(delay), failure);
        tasks.put(id, updated);
        return updated;
    }

    @Override
    public synchronized Optional<java.time.Instant> nextDueAt(Set<String> types) {
        java.time.Instant earliest = null;
        for (TaskRecord record : tasks.values()) {
            if (record.status() != TaskStatus.QUEUED || !types.contains(record.type())) continue;
            java.time.Instant due = record.runAfter();
            if (due == null) return Optional.of(clock.instant());     // something is due now
            if (earliest == null || due.isBefore(earliest)) earliest = due;
        }
        return Optional.ofNullable(earliest);
    }

    @Override
    public synchronized List<TaskRecord> sweepRunning(String reason) {
        List<TaskRecord> swept = new ArrayList<>();
        for (Map.Entry<String, TaskRecord> entry : tasks.entrySet()) {
            if (entry.getValue().status() == TaskStatus.RUNNING) {
                TaskRecord failed = entry.getValue().failed(
                        TaskFailure.of(reason, entry.getValue().attempt()));
                entry.setValue(failed);
                swept.add(failed);
            }
        }
        return swept;
    }
}
