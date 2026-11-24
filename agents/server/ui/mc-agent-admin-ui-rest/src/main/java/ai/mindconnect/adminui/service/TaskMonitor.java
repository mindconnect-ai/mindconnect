package ai.mindconnect.adminui.service;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.management.api.ManagedSession;
import ai.mindconnect.agent.management.api.SessionManagement;
import ai.mindconnect.agent.runtime.engine.service.RuntimeTask;
import ai.mindconnect.agent.sdk.AgentRuntime;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.channel.Channel;
import ai.mindconnect.channel.ChannelRegistry;
import ai.mindconnect.channel.Subscription;
import ai.mindconnect.namespace.service.NamespaceService;
import ai.mindconnect.namespace.service.ThreadBoundScopeSupplier;
import ai.mindconnect.taskqueue.TaskListener;
import ai.mindconnect.taskqueue.TaskRecord;
import ai.mindconnect.taskqueue.TaskStatus;
import ai.mindconnect.taskqueue.local.LocalTaskQueue;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The task manager behind the header's task badge: what the task queue is
 * doing right now, who it is doing it for, and the one thing an operator may
 * do about it — cancel a task of their own.
 *
 * <p>Three parts, one class because they share the snapshot:
 * <ul>
 *   <li><b>Observation.</b> A {@link TaskListener} on the queue. Every
 *       transition marks the view dirty; a short debounce turns a burst of
 *       events (a turn dispatching five tool calls) into one snapshot.</li>
 *   <li><b>Delivery.</b> Snapshots go onto a {@link Channel}; every
 *       browser tab is a subscriber with its own bounded queue — a slow tab
 *       never slows the queue, and the listener callback itself only flips
 *       a flag. The wire side — the SSE connection, rendering each snapshot
 *       for the user who is looking — is {@link UserStream}'s: the board
 *       shares the user's stream instead of holding a connection of its
 *       own.</li>
 *   <li><b>Ownership.</b> A task belongs to whoever owns the session it runs
 *       for ({@code payload.sessionId} → {@link ManagedSession#userId()}); a
 *       sub-agent's session inherits its parent's user, so a whole task tree
 *       has one owner. Cancel is allowed for that user only.</li>
 * </ul>
 */
@Component
public class TaskMonitor implements TaskListener {

    private static final Logger log = LoggerFactory.getLogger(TaskMonitor.class);

    /** The board's channel in the registry — and the DOM id of the header badge. */
    public static final String CHANNEL_ID = "task-monitor";

    /** Events within this window collapse into one snapshot. */
    static final long DEBOUNCE_MS = 250;
    /** Finished tasks shown under the live ones — a glance at what just happened. */
    static final int RECENT_LIMIT = 15;
    private static final int QUERY_LIMIT = 1000;

    /** One task, resolved into words for a person: what it does and for whom. */
    public record TaskView(TaskRecord task, String label, String detail, String owner, SessionId sessionId) {
        public String id() { return task.id(); }
        /** The namespace the task was submitted in; empty for a task nobody stamped. */
        public Optional<ai.mindconnect.agent.Namespace> namespace() {
            return namespaceOf(task);
        }
        public TaskStatus status() { return task.status(); }
        public boolean active() { return !task.status().terminal(); }
    }

    /** The whole board at one instant. */
    public record Snapshot(List<TaskView> active, List<TaskView> recent, Instant at) {
        /** The board as one namespace sees it: its own tasks, plus any nobody stamped. */
        public Snapshot in(ai.mindconnect.agent.Namespace namespace) {
            if (namespace == null) return this;
            return new Snapshot(
                    active.stream().filter(v -> v.namespace().map(namespace::equals).orElse(true)).toList(),
                    recent.stream().filter(v -> v.namespace().map(namespace::equals).orElse(true)).toList(),
                    at);
        }
        public int runningCount() { return count(TaskStatus.RUNNING); }
        public int queuedCount() { return count(TaskStatus.QUEUED); }
        public int suspendedCount() { return count(TaskStatus.SUSPENDED); }
        public Counts counts() { return new Counts(runningCount(), queuedCount() + suspendedCount()); }
        private int count(TaskStatus status) {
            return (int) active.stream().filter(v -> v.status() == status).count();
        }
    }

    /**
     * Just the numbers on the badge — what every page render asks for.
     * Cheap on purpose: no session lookups, only the queue's own counters.
     *
     * @param waiting queued plus suspended: submitted and not yet done, but
     *                not on a thread right now either
     */
    public record Counts(int running, int waiting) {
        public boolean busy() { return running + waiting > 0; }
    }

    /** Why a cancel did or did not happen. */
    public enum CancelResult { CANCELLED, NOT_OWNER, ALREADY_FINISHED, UNKNOWN }

    private final LocalTaskQueue queue;
    private final AgentRuntime runtime;

    private final Channel<Snapshot> channel = new ChannelRegistry().channel(CHANNEL_ID);
    private final AtomicBoolean pending = new AtomicBoolean();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "task-monitor");
        t.setDaemon(true);
        return t;
    });

    /** Where lookups run: a task's session lives in the task's namespace, and the board shows every namespace. */
    private final ScopeSupplier scope;

    /**
     * Which namespaces still exist. A finished task outlives the namespace it ran in, and looking
     * its session up there would open that namespace's stores again — with file persistence, its
     * directory.
     */
    private final NamespaceService namespaces;

    /** A host without namespaces — lookups run wherever the repositories are bound. */
    public TaskMonitor(LocalTaskQueue queue, AgentRuntime runtime) {
        this(queue, runtime, (ScopeSupplier) null, (NamespaceService) null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TaskMonitor(LocalTaskQueue queue,
                       AgentRuntime runtime,
                       org.springframework.beans.factory.ObjectProvider<ScopeSupplier> scope,
                       org.springframework.beans.factory.ObjectProvider<NamespaceService> namespaces) {
        this(queue, runtime, scope.getIfAvailable(), namespaces.getIfAvailable());
    }

    TaskMonitor(LocalTaskQueue queue,
                AgentRuntime runtime,
                ScopeSupplier scope,
                NamespaceService namespaces) {
        this.queue = queue;
        this.runtime = runtime;
        this.scope = scope;
        this.namespaces = namespaces;
        queue.addListener(this);
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    // ── observation ─────────────────────────────────────────────────────────

    @Override public void onSubmitted(TaskRecord task) { touch(); }
    @Override public void onStarted(TaskRecord task) { touch(); }
    @Override public void onStateChanged(TaskRecord task) { touch(); }
    @Override public void onSuspended(TaskRecord task) { touch(); }
    @Override public void onWoken(TaskRecord task) { touch(); }
    @Override public void onTerminal(TaskRecord task) { touch(); }

    /**
     * Marks the board dirty and schedules one publish. Runs on the queue's
     * transition thread, so it does nothing but flip a flag — the snapshot
     * (repository lookups included) is taken on the monitor's own thread.
     */
    private void touch() {
        if (scheduler.isShutdown()) return;          // the queue outlives us on shutdown
        if (pending.compareAndSet(false, true)) {
            try {
                scheduler.schedule(this::publish, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                pending.set(false);                  // shut down between the check and the call
            }
        }
    }

    private void publish() {
        pending.set(false);
        try {
            channel.publish(snapshot());
        } catch (RuntimeException e) {
            log.warn("Task monitor snapshot failed: {}", e.toString());
        }
    }

    // ── the board ───────────────────────────────────────────────────────────

    /** The badge's numbers right now, for the page render. */
    public Counts counts() {
        return counts(null);
    }

    /** The badge's numbers as one namespace sees them; null counts everything. */
    public Counts counts(ai.mindconnect.agent.Namespace namespace) {
        int running = countIn(TaskStatus.RUNNING, namespace);
        int waiting = countIn(TaskStatus.QUEUED, namespace) + countIn(TaskStatus.SUSPENDED, namespace);
        return new Counts(running, waiting);
    }

    private int countIn(TaskStatus status, ai.mindconnect.agent.Namespace namespace) {
        List<TaskRecord> records = queue.byStatus(status, QUERY_LIMIT);
        if (namespace == null) return records.size();
        return (int) records.stream().filter(task -> inNamespace(task, namespace)).count();
    }

    /** Whether {@code task} belongs to {@code namespace} — or to nobody, which every namespace sees. */
    static boolean inNamespace(TaskRecord task, ai.mindconnect.agent.Namespace namespace) {
        return namespaceOf(task).map(namespace::equals).orElse(true);
    }

    /** The namespace the task was submitted in; empty for a task that carries none. */
    private static Optional<ai.mindconnect.agent.Namespace> namespaceOf(TaskRecord task) {
        return RuntimeTask.of(task).map(RuntimeTask::namespace).map(ai.mindconnect.agent.Namespace::new);
    }

    /** The board right now — for the dialog; the stream sends the same. */
    public Snapshot snapshot() {
        Map<SessionId, Optional<ManagedSession>> sessionCache = new HashMap<>();
        Map<ai.mindconnect.agent.Namespace, Boolean> existing = new HashMap<>();

        List<TaskRecord> active = new ArrayList<>();
        active.addAll(queue.byStatus(TaskStatus.RUNNING, QUERY_LIMIT));
        active.addAll(queue.byStatus(TaskStatus.SUSPENDED, QUERY_LIMIT));
        active.addAll(queue.byStatus(TaskStatus.QUEUED, QUERY_LIMIT));
        active.sort(Comparator.comparing(TaskRecord::submittedAt, Comparator.nullsLast(Comparator.naturalOrder())));

        List<TaskRecord> finished = new ArrayList<>();
        finished.addAll(queue.byStatus(TaskStatus.COMPLETED, QUERY_LIMIT));
        finished.addAll(queue.byStatus(TaskStatus.FAILED, QUERY_LIMIT));
        finished.addAll(queue.byStatus(TaskStatus.CANCELLED, QUERY_LIMIT));
        finished.sort(Comparator.comparing(TaskRecord::endedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        if (finished.size() > RECENT_LIMIT) finished = finished.subList(0, RECENT_LIMIT);

        return new Snapshot(
                active.stream().map(t -> view(t, sessionCache, existing)).toList(),
                finished.stream().map(t -> view(t, sessionCache, existing)).toList(),
                Instant.now());
    }

    private TaskView view(TaskRecord task,
                          Map<SessionId, Optional<ManagedSession>> sessionCache,
                          Map<ai.mindconnect.agent.Namespace, Boolean> existing) {
        Optional<RuntimeTask> known = RuntimeTask.of(task);
        SessionId sessionId = sessionIdOf(known);
        // The session lives in the task's namespace: look it up there, whatever namespace the
        // monitor's own thread is in. The cache stays correct because a session id is a UUID —
        // the same id in two namespaces is not a case worth a compound key.
        // A task of a namespace deleted since shows without it: no owner, agent or title.
        Optional<ManagedSession> session = sessionId == null
                ? Optional.empty()
                : sessionCache.computeIfAbsent(sessionId, id -> inScopeOf(task, existing, () -> findSession(id)));

        String label;
        String detail;
        if (known.isEmpty()) {
            label = task.type();
            detail = null;
        } else if (known.get().kind() == RuntimeTask.Kind.TURN) {
            String agentName = known.get().agentName();
            label = agentName == null ? "Agent turn" : agentName;
            detail = describeTurn(task, session.orElse(null));
        } else {
            String agentName = session.flatMap(found -> inScopeOf(task, existing, () -> agentNameOf(found)))
                    .orElse(null);
            label = known.get().toolName() == null ? "Tool call" : known.get().toolName();
            detail = agentName == null ? "tool call" : "tool call by " + agentName;
        }
        String owner = session.map(ManagedSession::userId).map(user -> user.value()).orElse(null);
        return new TaskView(task, label, detail, owner, sessionId);
    }

    private static String describeTurn(TaskRecord task, ManagedSession session) {
        StringBuilder out = new StringBuilder();
        out.append(session != null && session.parentId() != null ? "sub-agent turn" : "turn");
        if (session != null && session.title() != null && !session.title().isBlank()) {
            out.append(" · ").append(session.title());
        }
        Object rounds = task.state().get("rounds");
        if (rounds != null) out.append(" · round ").append(rounds);
        return out.toString();
    }

    /**
     * Runs {@code body} bound to the namespace the task was submitted in, when there is one to bind —
     * and finds nothing, without touching that namespace's stores, when it no longer exists.
     */
    private <T> Optional<T> inScopeOf(TaskRecord task, Map<ai.mindconnect.agent.Namespace, Boolean> existing,
                                      java.util.function.Supplier<Optional<T>> body) {
        if (scope instanceof ThreadBoundScopeSupplier bound) {
            Optional<RuntimeTask> stamped = RuntimeTask.of(task).filter(known -> known.namespace() != null);
            if (stamped.isEmpty()) return body.get();
            ai.mindconnect.agent.Namespace namespace = new ai.mindconnect.agent.Namespace(stamped.get().namespace());
            if (!existing.computeIfAbsent(namespace, this::exists)) return Optional.empty();
            return bound.runIn(ai.mindconnect.agent.Scope.of(namespace,
                    stamped.get().user() == null ? null : UserId.of(stamped.get().user())), body);
        }
        return body.get();
    }

    /** Whether {@code namespace} is still there; without a namespace service every one is. */
    private boolean exists(ai.mindconnect.agent.Namespace namespace) {
        if (namespaces == null) return true;
        try {
            return namespaces.find(namespace).isPresent();
        } catch (RuntimeException e) {
            return false;                            // unknown is not a reason to open its stores
        }
    }

    private Optional<ManagedSession> findSession(SessionId id) {
        try {
            return runtime.service(SessionManagement.class)
                    .find(id);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private Optional<String> agentNameOf(ManagedSession session) {
        try {
            return runtime.service(SessionManagement.class).agentOf(session.id()).map(agent -> agent.name());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** The session a task runs for; a task that names none, or a value that is no id, has no session. */
    private static SessionId sessionIdOf(Optional<RuntimeTask> task) {
        try {
            return task.map(RuntimeTask::sessionId).map(SessionId::of).orElse(null);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ── cancel ──────────────────────────────────────────────────────────────

    /** Whether {@code userId} may cancel this task: their own, and not finished yet. */
    public static boolean mayCancel(TaskView view, String userId) {
        return view.active() && !view.task().cancelRequested()
                && view.owner() != null && view.owner().equals(userId);
    }

    /**
     * Cancels {@code taskId} on behalf of {@code userId}. The queue cascades
     * to the task's children, so cancelling a turn takes its tool calls and
     * sub-agents with it.
     */
    public CancelResult cancel(String taskId, String userId) {
        Optional<TaskRecord> record = queue.get(taskId);
        if (record.isEmpty()) return CancelResult.UNKNOWN;
        if (record.get().status().terminal()) return CancelResult.ALREADY_FINISHED;
        TaskView view = view(record.get(), new HashMap<>(), new HashMap<>());
        if (view.owner() == null || !view.owner().equals(userId)) return CancelResult.NOT_OWNER;
        return queue.cancel(taskId) ? CancelResult.CANCELLED : CancelResult.ALREADY_FINISHED;
    }

    // ── delivery ────────────────────────────────────────────────────────────

    /**
     * Every snapshot from now on, live only: whoever subscribes already
     * shows the current board, so there is nothing to replay. The event's
     * {@code seq} is the board's own sequence.
     */
    public Subscription subscribe(Consumer<Channel.Event<Snapshot>> consumer) {
        return channel.subscribe(channel.lastSeq(), consumer);
    }
}
