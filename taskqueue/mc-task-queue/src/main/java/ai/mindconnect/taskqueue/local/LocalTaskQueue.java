package ai.mindconnect.taskqueue.local;

import ai.mindconnect.taskqueue.TaskAdvisor;
import ai.mindconnect.taskqueue.RetryPolicy;
import ai.mindconnect.taskqueue.TaskContext;
import ai.mindconnect.taskqueue.LeaseLostException;
import ai.mindconnect.taskqueue.TaskFailure;
import ai.mindconnect.taskqueue.TaskNotification;
import ai.mindconnect.taskqueue.TaskListener;
import ai.mindconnect.taskqueue.TaskOutcome;
import ai.mindconnect.taskqueue.TaskQueue;
import ai.mindconnect.taskqueue.TaskQueueException;
import ai.mindconnect.taskqueue.TaskRecord;
import ai.mindconnect.taskqueue.TaskStatus;
import ai.mindconnect.taskqueue.TaskStore;
import ai.mindconnect.taskqueue.TaskSubmission;
import ai.mindconnect.taskqueue.TaskWorker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * In-process queue: one dispatcher thread claims from the {@link TaskStore},
 * every task runs on its own virtual thread. Unbounded by default — resource
 * limits belong on the resources (e.g. an LLM-call semaphore), NOT on the
 * workers: a bounded worker pool deadlocks as soon as a parent task awaits a
 * child (concept 11). The optional {@code maxConcurrent} exists for tests and
 * for workloads that provably never await.
 *
 * <p>On construction the store is swept: RUNNING tasks from a previous,
 * crashed process fail with "interrupted by restart" — no more silent deaths.
 * SUSPENDED tasks survive a restart untouched: they hold no thread and
 * their wakeup condition is purely in the store.
 */
public final class LocalTaskQueue implements TaskQueue, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LocalTaskQueue.class);

    private final TaskStore store;
    private final Semaphore permits;   // null = unbounded
    private final Map<String, TaskWorker> workers = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<TaskRecord>> awaiters = new ConcurrentHashMap<>();
    private final List<TaskListener> listeners = new CopyOnWriteArrayList<>();
    private final List<TaskAdvisor> advisors = new CopyOnWriteArrayList<>();
    private final Map<String, Thread> runningThreads = new ConcurrentHashMap<>();
    private final Map<String, List<Runnable>> cancelHooks = new ConcurrentHashMap<>();
    private final Object wakeup = new Object();
    private volatile RetryPolicy retryPolicy =
            RetryPolicy.exponentialBackoff(java.time.Duration.ofMillis(200), java.time.Duration.ofMinutes(5));
    private volatile Duration maintenanceInterval = Duration.ofSeconds(20);
    private volatile Duration retention;   // null = keep terminal records forever
    private final Thread dispatcher;
    private final Thread maintenance;
    private volatile boolean open = true;

    public LocalTaskQueue(TaskStore store) {
        this(store, 0);
    }

    /** @param maxConcurrent 0 = unbounded (default; see class javadoc for the deadlock warning) */
    public LocalTaskQueue(TaskStore store, int maxConcurrent) {
        this.store = store;
        this.permits = maxConcurrent > 0 ? new Semaphore(maxConcurrent) : null;
        store.sweepRunning("interrupted by restart").forEach(record -> {
            log.warn("Swept orphaned task {} ({}) to FAILED", record.id(), record.type());
            // The swept task leaves through the same door as every other —
            // finish() wakes its waiters atomically. (No listeners exist yet
            // at construction time, so there is no onTerminal to fire.)
            store.finish(record);
        });
        this.dispatcher = Thread.ofPlatform().name("task-queue-dispatcher").daemon(true)
                .start(this::dispatchLoop);
        this.maintenance = Thread.ofPlatform().name("task-queue-maintenance").daemon(true)
                .start(this::maintenanceLoop);
    }

    /**
     * How often leases are renewed and expired ones reclaimed. Must be well
     * under the store's lease duration — renew three times per lease and one
     * missed round is a hiccup instead of a takeover. No effect on a store
     * without leases, where both operations are no-ops.
     */
    public LocalTaskQueue withMaintenanceInterval(Duration interval) {
        this.maintenanceInterval = interval;
        maintenance.interrupt();          // pick the new interval up now, not after the old sleep
        return this;
    }

    /** Observation hook — after-the-fact, exception-safe, must not block long. */
    /**
     * What happens after a failed attempt. The default backs off exponentially
     * for as many attempts as the task itself allows — which is one unless the
     * submission said otherwise, so the out-of-the-box behaviour is unchanged.
     */
    /**
     * How long finished task TREES stay readable before the maintenance loop
     * forgets them ({@link TaskStore#purgeTerminal}). Off by default: the
     * record is the answer to "what happened", and throwing answers away is
     * the operator's call — this setting IS that call, made once instead of
     * per sweep.
     */
    public LocalTaskQueue withRetention(Duration keepTerminal) {
        this.retention = keepTerminal;
        return this;
    }

    public LocalTaskQueue withRetryPolicy(RetryPolicy retryPolicy) {
        this.retryPolicy = retryPolicy;
        return this;
    }

    public LocalTaskQueue addListener(TaskListener listener) {
        listeners.add(listener);
        return this;
    }

    /** Policy hook — in-band; runs in {@link TaskAdvisor#order()} (lower = outermost). */
    public LocalTaskQueue addAdvisor(TaskAdvisor advisor) {
        advisors.add(advisor);
        advisors.sort(Comparator.comparingInt(TaskAdvisor::order));
        return this;
    }

    // ── TaskQueue ───────────────────────────────────────────────────────────

    @Override
    public String submit(TaskSubmission submission) {
        if (!open) throw new TaskQueueException("Queue is closed");
        TaskSubmission effective = submission;
        for (TaskAdvisor advisor : advisors) {
            effective = advisor.beforeSubmit(effective);   // may reject by throwing
        }
        String id = effective.id() != null ? effective.id() : "task_" + UUID.randomUUID();
        // Caller-chosen ids make submitting idempotent: the id names the WORK,
        // so a second submit of the same work is the same task — whatever
        // state it is in by now — never a twin. The insert is ATOMIC in the
        // store; two racing submits cannot both create (or overwrite) it.
        TaskRecord record = TaskRecord.queued(id, effective);
        Optional<TaskRecord> existing = store.insertIfAbsent(record);
        if (existing.isPresent()) {
            log.debug("Task {} already exists ({}), returning it instead of resubmitting",
                    id, existing.get().status());
            return id;
        }
        notifyListeners(l -> l.onSubmitted(record));
        signal();
        return id;
    }

    @Override
    public Optional<TaskRecord> get(String taskId) {
        return store.find(taskId);
    }

    /**
     * Cancels the task AND, recursively, its non-terminal children — the
     * task-manager kill: a parked parent dies together with the sub-tasks it
     * spawned. Cooperative for RUNNING tasks, immediate for QUEUED/WAITING.
     */
    @Override
    public boolean cancel(String taskId) {
        Optional<TaskRecord> updated = store.requestCancel(taskId);
        for (TaskRecord child : store.byParent(taskId)) {
            if (!child.status().terminal()) cancel(child.id());
        }
        if (updated.isEmpty()) return false;
        TaskRecord record = updated.get();
        if (record.status() == TaskStatus.CANCELLED) {
            turnTerminal(record);                  // was QUEUED or SUSPENDED — nothing running
            signal();
        } else {
            runHooks(taskId);                         // tear down in-flight work at once
            Thread thread = runningThreads.get(taskId);
            if (thread != null) thread.interrupt();   // best-effort accelerator
        }
        return true;
    }

    @Override
    public TaskRecord await(String taskId, Duration timeout) {
        TaskRecord current = store.find(taskId)
                .orElseThrow(() -> new TaskQueueException("Unknown task " + taskId));
        if (current.status().terminal()) return current;
        CompletableFuture<TaskRecord> future =
                awaiters.computeIfAbsent(taskId, id -> new CompletableFuture<>());
        // re-check after registering — the task may have finished in between
        TaskRecord recheck = store.find(taskId).orElseThrow();
        if (recheck.status().terminal()) {
            awaiters.remove(taskId);
            return recheck;
        }
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new TaskQueueException("Timed out awaiting task " + taskId + " after " + timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TaskQueueException("Interrupted awaiting task " + taskId);
        } catch (ExecutionException e) {
            throw new TaskQueueException("Await failed for task " + taskId + ": " + e.getMessage());
        }
    }

    @Override
    public void register(String taskType, TaskWorker worker) {
        workers.put(taskType, worker);
        signal();
    }

    @Override
    public boolean hasRegisteredType(String type) {
        return workers.containsKey(type);
    }

    /**
     * Shutting down is not cancelling: this node stops, the WORK does not.
     * Running tasks are interrupted and — see the {@code !open} branch in
     * {@code run} — handed back to the store as QUEUED, so another node (or
     * this one after a restart) picks them up and continues from their state
     * cursor. The short join gives those hand-backs time to reach the store
     * before the JVM goes; whatever misses the window is caught by the
     * lease/startup sweep instead.
     */
    @Override
    public void close() {
        open = false;
        dispatcher.interrupt();
        maintenance.interrupt();
        List<Thread> running = List.copyOf(runningThreads.values());
        running.forEach(Thread::interrupt);
        long deadline = System.currentTimeMillis() + 5000;
        for (Thread thread : running) {
            try {
                long left = deadline - System.currentTimeMillis();
                if (left > 0) thread.join(left);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // ── maintenance: keep our leases, reclaim the dead ones ─────────────────

    /**
     * Two halves of the same contract with a leasing store: tell it we are
     * still alive for what we run, and pick up what a node that stopped
     * saying so left behind. Both are no-ops on a single-process store, so
     * this loop costs a map iteration every {@code maintenanceInterval}.
     */
    private void maintenanceLoop() {
        while (open) {
            try {
                Thread.sleep(maintenanceInterval.toMillis());
                for (String id : runningThreads.keySet()) {
                    if (!store.renewLease(id)) {
                        // Someone else owns this attempt now. We do not kill our
                        // own thread over it: at-least-once is the contract, and
                        // an interrupt mid-flight is the worse of the two evils.
                        log.warn("Lost the lease on running task {} — it has been taken over elsewhere", id);
                    }
                }
                Duration keep = retention;
                if (keep != null) {
                    int purged = store.purgeTerminal(java.time.Instant.now().minus(keep));
                    if (purged > 0) {
                        log.info("Retention: forgot {} finished task record(s) older than {}",
                                purged, keep);
                    }
                }
                for (TaskRecord recovered : store.recoverExpired()) {
                    log.warn("Reclaimed task {} ({}) from an expired lease → {}",
                            recovered.id(), recovered.type(), recovered.status());
                    if (recovered.status().terminal()) {
                        turnTerminal(recovered);
                    } else {
                        notifyListeners(l -> l.onWoken(recovered));
                    }
                    signal();
                }
            } catch (InterruptedException e) {
                // close() or withMaintenanceInterval() — the loop condition decides which
            } catch (RuntimeException e) {
                log.error("Queue maintenance failed, continuing", e);
            }
        }
    }

    // ── dispatch ────────────────────────────────────────────────────────────

    private void dispatchLoop() {
        while (open) {
            boolean permitHeld = false;
            try {
                if (permits != null) {
                    permits.acquire();
                    permitHeld = true;
                }
                Optional<TaskRecord> claimed = store.claimNext(workers.keySet());
                if (claimed.isEmpty()) {
                    if (permits != null) {
                        permits.release();
                        permitHeld = false;   // released HERE — the catch must not release again
                    }
                    synchronized (wakeup) {
                        // Sleep until the next task is due, but never longer than
                        // the old fixed tick — that timeout still catches
                        // submit/claim races.
                        long waitMs = 500;
                        java.util.Optional<java.time.Instant> due = store.nextDueAt(workers.keySet());
                        if (due.isPresent()) {
                            long untilDue = java.time.Duration.between(
                                    java.time.Instant.now(), due.get()).toMillis();
                            waitMs = Math.max(1, Math.min(waitMs, untilDue));
                        }
                        wakeup.wait(waitMs);
                    }
                    continue;
                }
                TaskRecord record = claimed.get();
                permitHeld = false;   // the run thread owns it from here
                Thread.ofVirtual().name("task-" + record.id()).start(() -> run(record));
            } catch (InterruptedException e) {
                // close() interrupts the dispatcher — loop exits via !open
                if (permitHeld && permits != null) permits.release();
            } catch (RuntimeException e) {
                log.error("Dispatcher error, continuing", e);
                if (permitHeld && permits != null) permits.release();
            }
        }
    }

    @Override
    public List<TaskRecord> children(String taskId) {
        return store.byParent(taskId);
    }

    @Override
    public List<TaskRecord> byStatus(TaskStatus status, int limit) {
        return store.byStatus(status, limit);
    }

    @Override
    public boolean notify(String taskId, TaskNotification notification) {
        java.util.Optional<TaskRecord> woken = store.notify(taskId, notification);
        woken.ifPresent(record -> notifyListeners(l -> l.onWoken(record)));
        signal();
        return store.find(taskId).map(r -> !r.status().terminal()).orElse(false);
    }

    private void run(TaskRecord record) {
        TaskWorker worker = workers.get(record.type());
        runningThreads.put(record.id(), Thread.currentThread());
        notifyListeners(l -> l.onStarted(record));
        TaskOutcome outcome = null;
        Throwable thrown = null;
        try {
            // Delivery: whatever arrived since the last round is handed to this
            // execution and leaves the mailbox. Messages sent while we run wait
            // for the next round — that is what makes a notify-during-RUNNING safe.
            List<TaskNotification> delivered = store.drainNotifications(record.id());
            outcome = executeThroughAdvisors(worker, new Context(record.id(), record, delivered));
        } catch (Throwable t) {
            // The queue records the failure — a worker throws and is done.
            // Throwable on purpose: an AssertionError or LinkageError must
            // fail the ATTEMPT, not silently kill the transition below.
            thrown = t;
        } finally {
            // The round is over here — deregister BEFORE any transition that
            // could make this task claimable again. A store.suspend that
            // requeues straight away (everything awaited already terminal, or a
            // message waiting) hands the task to another dispatcher thread at
            // once, and that thread's own registration must not be wiped by
            // this one's cleanup.
            runningThreads.remove(record.id());
            cancelHooks.remove(record.id());
        }

        // The transition below is the queue's promise-keeping: whatever
        // happens, the permit comes back and the dispatcher is signalled —
        // and a fenced-out attempt (lease taken over) ends SILENTLY, because
        // every terminal side effect now belongs to the attempt that owns
        // the task.
        try {
            finishRound(record, outcome, thrown);
        } catch (LeaseLostException e) {
            log.warn("Attempt {} of task {} was fenced out — another node owns it now: {}",
                    record.attempt(), record.id(), e.getMessage());
        } catch (RuntimeException e) {
            log.error("Could not persist the outcome of task {} — it stays RUNNING for "
                    + "lease/startup recovery", record.id(), e);
        } finally {
            if (permits != null) permits.release();
            signal();
        }
    }

    private void finishRound(TaskRecord record, TaskOutcome outcome, Throwable thrown) {
        TaskRecord next;
        if (cancelRequested(record.id())) {
            next = current(record).cancelled();
        } else if (thrown != null) {
            if (!open) {
                // The queue is closing, not the task failing: hand the work
                // back immediately. Deliberately outside the retry policy and
                // its attempt budget — a node shutting down is operations,
                // not a failed attempt.
                next = store.retry(record.id(), java.time.Duration.ZERO,
                        TaskFailure.of("node shut down — task handed back", record.attempt()));
            } else {
                TaskFailure failure = TaskFailure.of(thrown, record.attempt());
                java.util.Optional<java.time.Duration> delay =
                        retryPolicy.retryIn(current(record), failure);
                next = delay.isPresent()
                        ? store.retry(record.id(), delay.get(), failure)   // another attempt, later
                        : current(record).failed(failure);
            }
        } else if (outcome instanceof TaskOutcome.Suspend suspend) {
            // continuation: suspend without a thread; the store requeues
            // straight away when there is nothing left to wait for
            next = store.suspend(record.id(), suspend);
        } else {
            next = current(record).completed(((TaskOutcome.Completed) outcome).result());
        }
        if (next.status().terminal()) {
            turnTerminal(next);
        } else if (next.status() == TaskStatus.SUSPENDED) {
            TaskRecord suspended = next;
            notifyListeners(l -> l.onSuspended(suspended));
        } else {
            TaskRecord requeued = next;                     // suspend on already-terminal tasks
            notifyListeners(l -> l.onWoken(requeued));
        }
        // SUSPENDED/QUEUED were already persisted by store.suspend
    }

    /** Builds the advisor chain around the worker; lower order = outermost. */
    private TaskOutcome executeThroughAdvisors(TaskWorker worker, TaskContext ctx) throws Exception {
        TaskAdvisor.Execution chain = worker::execute;
        for (int i = advisors.size() - 1; i >= 0; i--) {
            TaskAdvisor advisor = advisors.get(i);
            TaskAdvisor.Execution inner = chain;
            chain = c -> advisor.aroundExecute(c, inner);
        }
        return chain.proceed(ctx);
    }

    private void notifyListeners(java.util.function.Consumer<TaskListener> callback) {
        for (TaskListener listener : listeners) {
            try {
                callback.accept(listener);
            } catch (RuntimeException e) {
                log.warn("Task listener {} threw: {}", listener.getClass().getName(), e.toString());
            }
        }
    }

    /** Transitions must build on the LIVE record — the claim-time snapshot has stale state. */
    private TaskRecord current(TaskRecord snapshot) {
        return store.find(snapshot.id()).orElse(snapshot);
    }

    private void runHooks(String taskId) {
        List<Runnable> hooks = cancelHooks.get(taskId);
        if (hooks == null) return;
        for (Runnable hook : List.copyOf(hooks)) {
            try {
                hook.run();
            } catch (RuntimeException e) {
                log.warn("Cancel hook of task {} threw: {}", taskId, e.toString());
            }
        }
    }

    private boolean cancelRequested(String taskId) {
        return store.find(taskId).map(TaskRecord::cancelRequested).orElse(false);
    }

    /**
     * The queue's side of the one door: the store makes the termination
     * durable and audible in one step ({@code finish}), then the process-local
     * promises are kept — awaiters completed, listeners told, woken tasks
     * announced. Copied three times before it was one method; the copies had
     * already started to drift.
     */
    private void turnTerminal(TaskRecord terminal) {
        List<TaskRecord> woken = store.finish(terminal);
        completeAwaiters(terminal);
        notifyListeners(l -> l.onTerminal(terminal));
        woken.forEach(w -> notifyListeners(l -> l.onWoken(w)));
    }

    private void completeAwaiters(TaskRecord terminal) {
        CompletableFuture<TaskRecord> future = awaiters.remove(terminal.id());
        if (future != null) future.complete(terminal);
    }

    private void signal() {
        synchronized (wakeup) {
            wakeup.notifyAll();
        }
    }

    /**
     * "Something changed in the store — look now." The cross-process cousin of
     * the in-process signal: with a shared store, another node's submit or
     * wake is invisible to this dispatcher until its next poll tick, and a
     * nudge (delivered any way at all — REST, a message, a pipe) turns that
     * wait into an immediate claim. Purely an accelerator: a lost nudge costs
     * one poll interval, never a task.
     */
    public void nudge() {
        signal();
    }

    private final class Context implements TaskContext {
        private final String taskId;
        private final TaskRecord snapshot;
        private final List<TaskNotification> delivered;

        Context(String taskId, TaskRecord snapshot, List<TaskNotification> delivered) {
            this.taskId = taskId;
            this.snapshot = snapshot;
            this.delivered = List.copyOf(delivered);
        }

        @Override public String submitChild(TaskSubmission submission) {
            TaskSubmission child = submission.withParent(taskId);
            // A sub-task outranks a fresh root unless the caller said otherwise:
            // the parent is already parked waiting for it.
            if (child.priority() == 0) child = child.withPriority(1);
            return LocalTaskQueue.this.submit(child);
        }

        @Override public String submitChild(String type, java.util.Map<String, Object> payload) {
            return submitChild(TaskSubmission.of(type, payload));
        }

        @Override public List<TaskRecord> children() {
            return LocalTaskQueue.this.children(taskId);
        }

        @Override public List<TaskNotification> notifications() { return delivered; }

        @Override public boolean notifyTask(String otherTaskId, java.util.Map<String, Object> payload) {
            return LocalTaskQueue.this.notify(otherTaskId, TaskNotification.from(taskId, payload));
        }

        @Override public boolean notifyParent(java.util.Map<String, Object> payload) {
            String parent = snapshot.parentTaskId();
            return parent != null && notifyTask(parent, payload);
        }

        @Override public TaskRecord task() { return snapshot; }

        @Override public boolean cancelRequested() { return LocalTaskQueue.this.cancelRequested(taskId); }

        @Override public void onCancel(Runnable hook) {
            if (cancelRequested()) {
                hook.run();
                return;
            }
            cancelHooks.computeIfAbsent(taskId, id -> new CopyOnWriteArrayList<>()).add(hook);
        }

        @Override public java.util.Map<String, Object> state() {
            return store.find(taskId).map(TaskRecord::state).orElse(java.util.Map.of());
        }

        @Override public void updateState(java.util.Map<String, Object> state) {
            store.updateState(taskId, state);
            store.find(taskId).ifPresent(updated -> notifyListeners(l -> l.onStateChanged(updated)));
        }
    }
}
