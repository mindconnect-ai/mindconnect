package ai.mindconnect.taskqueue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One task's persisted state — queue entry, status record and audit row in
 * one. Immutable; transitions create copies via the {@code with*} helpers.
 *
 * <p>{@code status} vs. {@code state}: {@code status} is the QUEUE's
 * lifecycle enum; {@code state} is the WORKER's own data map — live progress
 * while RUNNING (written via {@link TaskContext#updateState}, rendered by
 * admin UIs), continuation cursor across SUSPENDED, post-mortem context on
 * terminal records. Data-only and small; big state belongs in domain storage
 * (an agent turn's real state is its transcript).
 *
 * @param nodeId          who ran (or runs) the LAST attempt — stamped at claim
 *                        time and kept on the terminal record, so "which node
 *                        finished this" stays answerable after the lease is
 *                        long gone. Null until first claimed
 * @param attempt         how often a worker has claimed this task. More than 1
 *                        means a retry, a crash recovery — or a resume after
 *                        {@link TaskStatus#SUSPENDED} (delivery is at-least-once)
 * @param cancelRequested cooperative cancel flag; the worker checks it via
 *                        {@link TaskContext#cancelRequested()}
 * @param waitingFor      the not-yet-terminal remainder of this task's join —
 *                        kept honest by the store across notification wakes
 * @param notifications   undelivered messages from other tasks. Filled whatever
 *                        the status is, drained when a worker claims the task —
 *                        a notification can therefore never be missed by a task
 *                        that has not suspended yet
 * @param resumed         this task has suspended before, so the next execution
 *                        continues where it left off — read it through
 *                        {@link TaskContext#isResumed()}. Sticks once set: a
 *                        crash retry of a resumed task is still a continuation
 * @param result          worker return value on COMPLETED (nullable — domain
 *                        results usually live in domain storage, this is a
 *                        short summary/pointer)
 * @param failure         what went wrong on the last attempt — recorded by the
 *                        queue, kept across a retry so it is still readable
 *                        while the task waits for its next attempt
 * @param runAfter        not claimable before this instant; {@code null} means
 *                        "as soon as a worker is free". Carries both a delay
 *                        and a fixed point in time, and is what a retry sets
 *                        to space out attempts
 * @param maxAttempts     how often this task may be claimed in total; 1 (the
 *                        default) means no retry
 */
public record TaskRecord(
        String id,
        String type,
        TaskStatus status,
        Map<String, Object> payload,
        int priority,
        String parentTaskId,
        int attempt,
        String nodeId,
        boolean cancelRequested,
        Set<String> waitingFor,
        List<TaskNotification> notifications,
        boolean resumed,
        Map<String, Object> state,
        String result,
        TaskFailure failure,
        Instant runAfter,
        int maxAttempts,
        Instant submittedAt,
        Instant startedAt,
        Instant endedAt
) {

    public static TaskRecord queued(String id, TaskSubmission submission) {
        return new TaskRecord(id, submission.type(), TaskStatus.QUEUED,
                Map.copyOf(submission.payload()), submission.priority(), submission.parentTaskId(),
                0, null, false, Set.of(), List.of(), false, Map.of(), null, null,
                submission.runAfter(), Math.max(1, submission.maxAttempts()),
                Instant.now(), null, null);
    }

    /** @param nodeId the node this attempt runs on — a fact of the claim */
    public TaskRecord claimed(String nodeId) {
        return new TaskRecord(id, type, TaskStatus.RUNNING, payload, priority, parentTaskId,
                attempt + 1, nodeId, cancelRequested, waitingFor, notifications, resumed, state, result, failure, null, maxAttempts,
                submittedAt, Instant.now(), null);
    }

    public TaskRecord completed(String result) {
        return new TaskRecord(id, type, TaskStatus.COMPLETED, payload, priority, parentTaskId,
                attempt, nodeId, cancelRequested, Set.of(), notifications, resumed, state, result, null, runAfter, maxAttempts,
                submittedAt, startedAt, Instant.now());
    }

    public TaskRecord failed(TaskFailure failure) {
        return new TaskRecord(id, type, TaskStatus.FAILED, payload, priority, parentTaskId,
                attempt, nodeId, cancelRequested, Set.of(), notifications, resumed, state, result, failure, runAfter, maxAttempts,
                submittedAt, startedAt, Instant.now());
    }

    public TaskRecord cancelled() {
        return new TaskRecord(id, type, TaskStatus.CANCELLED, payload, priority, parentTaskId,
                attempt, nodeId, true, Set.of(), notifications, resumed, state, result, failure, runAfter, maxAttempts,
                submittedAt, startedAt, Instant.now());
    }

    public TaskRecord withCancelRequested() {
        return new TaskRecord(id, type, status, payload, priority, parentTaskId,
                attempt, nodeId, true, waitingFor, notifications, resumed, state, result, failure, runAfter, maxAttempts,
                submittedAt, startedAt, endedAt);
    }

    /** The worker replaced its state map (live progress / continuation cursor). */
    public TaskRecord withState(Map<String, Object> state) {
        return new TaskRecord(id, type, status, payload, priority, parentTaskId,
                attempt, nodeId, cancelRequested, waitingFor, notifications, resumed, Map.copyOf(state), result, failure, runAfter, maxAttempts,
                submittedAt, startedAt, endedAt);
    }

    /** Suspended on the given tasks — no thread, no slot; {@code state} rides along. */
    /** Ledger-only update — status and everything else stay untouched. */
    public TaskRecord withWaitingFor(Set<String> waitingFor) {
        return new TaskRecord(id, type, status, payload, priority, parentTaskId,
                attempt, nodeId, cancelRequested, Set.copyOf(waitingFor), notifications, resumed, state, result, failure, runAfter, maxAttempts,
                submittedAt, startedAt, endedAt);
    }

    public TaskRecord suspended(Set<String> waitingFor) {
        return new TaskRecord(id, type, TaskStatus.SUSPENDED, payload, priority, parentTaskId,
                attempt, nodeId, cancelRequested, Set.copyOf(waitingFor), notifications, resumed, state, result, failure, runAfter, maxAttempts,
                submittedAt, startedAt, null);
    }

    /** Appends a message to the mailbox; the status is untouched. */
    public TaskRecord withNotification(TaskNotification notification) {
        List<TaskNotification> inbox = new ArrayList<>(notifications);
        inbox.add(notification);
        return new TaskRecord(id, type, status, payload, priority, parentTaskId,
                attempt, nodeId, cancelRequested, waitingFor, List.copyOf(inbox), resumed, state, result, failure, runAfter, maxAttempts,
                submittedAt, startedAt, endedAt);
    }

    /** Mailbox emptied because its messages were handed to a running worker. */
    public TaskRecord withDrainedMailbox() {
        return new TaskRecord(id, type, status, payload, priority, parentTaskId,
                attempt, nodeId, cancelRequested, waitingFor, List.of(), resumed, state, result, failure, runAfter, maxAttempts,
                submittedAt, startedAt, endedAt);
    }

    /**
     * Back in line for another attempt after {@code runAfter}. The failure that
     * caused it stays on the record, so what went wrong is readable while the
     * task waits rather than only after it finally gives up.
     */
    public TaskRecord retryAt(Instant runAfter, TaskFailure failure) {
        return new TaskRecord(id, type, TaskStatus.QUEUED, payload, priority, parentTaskId,
                attempt, nodeId, cancelRequested, waitingFor, notifications, resumed, state, result,
                failure, runAfter, maxAttempts, submittedAt, startedAt, null);
    }

    /** Whether a worker may pick this up now. */
    public boolean isDue(Instant now) {
        return runAfter == null || !runAfter.isAfter(now);
    }

    /**
     * The park decision itself — park on what is still {@code open}, or go
     * straight back into the queue. It belongs on the record rather than in a
     * store because it is the rule, not the storage: every {@link TaskStore}
     * has to decide it identically or the lost-wakeup races come back.
     *
     * <p>Park only when something can still wake us, and never while a message
     * is already waiting. Those two rules together close the race in both
     * directions — a notification that arrived before we parked is not slept
     * through, and a wait on nothing at all never becomes a task no event can
     * reach.
     *
     * @param waitingFor everything this task asked to wait for
     * @param open       the subset of those that is not terminal yet
     */
    /**
     * @param notificationWait this suspend deliberately waits for mail alone
     *        ({@code suspendUntilNotified()}). NOT derivable from an empty
     *        waiting set: {@code suspendUntilChildren()} with zero children
     *        also arrives empty — and that one must requeue, as documented,
     *        instead of parking on a notification nobody will send.
     */
    public TaskRecord parkedOn(Set<String> waitingFor, Set<String> open, boolean notificationWait) {
        boolean mailWaiting = !notifications.isEmpty();          // a message always wakes
        boolean canBeWoken = !open.isEmpty() || notificationWait;
        // Either way the record carries the honest remainder of the join —
        // never the asked set, never an emptied one.
        return mailWaiting || !canBeWoken
                ? suspended(open).requeued()
                : suspended(open);
    }

    /**
     * One awaited task turned terminal: drop it from the waiting set, and
     * requeue when it was the last one this task was waiting for.
     */
    public TaskRecord notWaitingFor(String terminalTaskId) {
        Set<String> remaining = new java.util.HashSet<>(waitingFor);
        remaining.remove(terminalTaskId);
        TaskRecord updated = suspended(remaining);
        return remaining.isEmpty() ? updated.requeued() : updated;
    }

    /**
     * Back in line — everything awaited turned terminal, or a notification
     * arrived. From here on this task is a continuation.
     *
     * <p>{@code waitingFor} survives the requeue untouched: it is always the
     * not-yet-terminal remainder of the join, and a notification wake must
     * not erase it — otherwise a parent woken by a message would read an
     * empty set and finish while its children still run.
     */
    public TaskRecord requeued() {
        return new TaskRecord(id, type, TaskStatus.QUEUED, payload, priority, parentTaskId,
                attempt, nodeId, cancelRequested, waitingFor, notifications, true, state, result, failure, runAfter, maxAttempts,
                submittedAt, startedAt, null);
    }
}
