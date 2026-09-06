package ai.mindconnect.adminui.service;

import ai.mindconnect.adminui.ui.component.TaskMonitorComponent;
import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.port.out.AgentSessionRepository;
import ai.mindconnect.agent.service.task.AgentTurnWorker;
import ai.mindconnect.agent.service.task.ToolCallWorker;
import ai.mindconnect.channel.Channel;
import ai.mindconnect.channel.ChannelRegistry;
import ai.mindconnect.channel.Subscription;
import ai.mindconnect.taskqueue.TaskListener;
import ai.mindconnect.taskqueue.TaskRecord;
import ai.mindconnect.taskqueue.TaskStatus;
import ai.mindconnect.taskqueue.local.LocalTaskQueue;
import ai.mindconnect.ui.model.UiPatch;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
 *   <li><b>Delivery.</b> Snapshots go onto a {@link Channel}, and every
 *       browser tab that opened the {@code /sse} endpoint is a subscriber
 *       with its own bounded queue — a slow tab never slows the queue, and
 *       the listener callback itself only flips a flag. Each subscriber is
 *       rendered for <em>its</em> user, because whether a Cancel button
 *       appears depends on who is looking.</li>
 *   <li><b>Ownership.</b> A task belongs to whoever owns the session it runs
 *       for ({@code payload.sessionId} → {@link AgentSession#userId()}); a
 *       sub-agent's session inherits its parent's user, so a whole task tree
 *       has one owner. Cancel is allowed for that user only.</li>
 * </ul>
 */
@Component
public class TaskMonitor implements TaskListener {

    private static final Logger log = LoggerFactory.getLogger(TaskMonitor.class);

    /**
     * Stream channel id AND the DOM id of the header badge. The SPA keeps a
     * stream attached only while an element with the channel's id is
     * mounted; the badge is on every admin page, so the stream survives
     * navigation and is never opened twice.
     */
    public static final String CHANNEL_ID = "task-monitor";

    /** Events within this window collapse into one snapshot. */
    static final long DEBOUNCE_MS = 250;
    /** SSE comment keeping idle connections alive through proxies. */
    private static final long HEARTBEAT_SECONDS = 20;
    /** Finished tasks shown under the live ones — a glance at what just happened. */
    static final int RECENT_LIMIT = 15;
    private static final int QUERY_LIMIT = 1000;

    /** One task, resolved into words for a person: what it does and for whom. */
    public record TaskView(TaskRecord task, String label, String detail, String owner, UUID sessionId) {
        public String id() { return task.id(); }
        public TaskStatus status() { return task.status(); }
        public boolean active() { return !task.status().terminal(); }
    }

    /** The whole board at one instant. */
    public record Snapshot(List<TaskView> active, List<TaskView> recent, Instant at) {
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
    private final AgentSessionRepository sessions;
    private final AgentDefinitionRepository definitions;
    private final ObjectMapper objectMapper;

    private final Channel<Snapshot> channel = new ChannelRegistry().channel(CHANNEL_ID);
    private final Map<SseEmitter, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final AtomicBoolean pending = new AtomicBoolean();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "task-monitor");
        t.setDaemon(true);
        return t;
    });

    public TaskMonitor(LocalTaskQueue queue,
                       AgentSessionRepository sessions,
                       AgentDefinitionRepository definitions,
                       ObjectMapper objectMapper) {
        this.queue = queue;
        this.sessions = sessions;
        this.definitions = definitions;
        this.objectMapper = objectMapper;
        queue.addListener(this);
        scheduler.scheduleWithFixedDelay(this::heartbeat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    @PreDestroy
    void shutdown() {
        for (var entry : subscriptions.entrySet()) {
            entry.getValue().close();
            try {
                entry.getKey().complete();
            } catch (Exception ignore) {
                // already gone
            }
        }
        subscriptions.clear();
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
        int running = queue.byStatus(TaskStatus.RUNNING, QUERY_LIMIT).size();
        int waiting = queue.byStatus(TaskStatus.QUEUED, QUERY_LIMIT).size()
                + queue.byStatus(TaskStatus.SUSPENDED, QUERY_LIMIT).size();
        return new Counts(running, waiting);
    }

    /** The board right now — for the dialog; the stream sends the same. */
    public Snapshot snapshot() {
        Map<UUID, Optional<AgentSession>> sessionCache = new HashMap<>();
        Map<UUID, Optional<AgentDefinition>> definitionCache = new HashMap<>();

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
                active.stream().map(t -> view(t, sessionCache, definitionCache)).toList(),
                finished.stream().map(t -> view(t, sessionCache, definitionCache)).toList(),
                Instant.now());
    }

    private TaskView view(TaskRecord task,
                          Map<UUID, Optional<AgentSession>> sessionCache,
                          Map<UUID, Optional<AgentDefinition>> definitionCache) {
        UUID sessionId = uuid(task.payload().get(AgentTurnWorker.SESSION_ID));
        Optional<AgentSession> session = sessionId == null
                ? Optional.empty()
                : sessionCache.computeIfAbsent(sessionId, this::findSession);
        Optional<AgentDefinition> agent = session
                .map(AgentSession::agentDefinitionId)
                .flatMap(id -> definitionCache.computeIfAbsent(id, this::findDefinition));

        String agentName = agent.map(AgentDefinition::name).orElse(null);
        String label;
        String detail;
        if (AgentTurnWorker.TYPE.equals(task.type())) {
            label = agentName == null ? "Agent turn" : agentName;
            detail = describeTurn(task, session.orElse(null));
        } else if (ToolCallWorker.TYPE.equals(task.type())) {
            Object toolName = task.payload().get(ToolCallWorker.TOOL_NAME);
            label = toolName == null ? "Tool call" : String.valueOf(toolName);
            detail = agentName == null ? "tool call" : "tool call by " + agentName;
        } else {
            label = task.type();
            detail = null;
        }
        String owner = session.map(AgentSession::userId).orElse(null);
        return new TaskView(task, label, detail, owner, sessionId);
    }

    private static String describeTurn(TaskRecord task, AgentSession session) {
        StringBuilder out = new StringBuilder();
        int depth = ((Number) task.payload().getOrDefault(AgentTurnWorker.DEPTH, 0)).intValue();
        out.append(depth == 0 ? "turn" : "sub-agent turn (depth " + depth + ")");
        if (session != null && session.title() != null && !session.title().isBlank()) {
            out.append(" · ").append(session.title());
        }
        Object rounds = task.state().get("rounds");
        if (rounds != null) out.append(" · round ").append(rounds);
        return out.toString();
    }

    private Optional<AgentSession> findSession(UUID id) {
        try {
            return sessions.findById(id);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private Optional<AgentDefinition> findDefinition(UUID id) {
        try {
            return definitions.findById(id);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static UUID uuid(Object value) {
        if (value == null) return null;
        try {
            return UUID.fromString(String.valueOf(value));
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
     * Subscribes a browser tab, live only: the page that opened the stream
     * already shows the current board, so there is nothing to replay. Every
     * snapshot from here on is rendered for {@code userId} and written as
     * one {@code patch} frame.
     */
    public void attach(SseEmitter emitter, String userId) {
        Subscription subscription = channel.subscribe(channel.lastSeq(),
                event -> send(emitter, event.seq(), event.value(), userId));
        Subscription previous = subscriptions.put(emitter, subscription);
        if (previous != null) previous.close();
    }

    public void detach(SseEmitter emitter) {
        Subscription subscription = subscriptions.remove(emitter);
        if (subscription != null) subscription.close();
    }

    private void send(SseEmitter emitter, long seq, Snapshot snapshot, String userId) {
        try {
            UiPatch patch = TaskMonitorComponent.livePatch(snapshot, userId);
            emitter.send(SseEmitter.event()
                    .id(Long.toString(seq))
                    .name("patch")
                    .data(objectMapper.writeValueAsString(patch)));
        } catch (Exception e) {
            // The client went away, or the socket is broken. The heartbeat
            // reaps it; the channel must not see the exception.
            detach(emitter);
        }
    }

    /** Keeps idle connections open and notices the ones that are gone. */
    private void heartbeat() {
        for (SseEmitter emitter : subscriptions.keySet()) {
            try {
                emitter.send(SseEmitter.event().comment("hb"));
            } catch (Exception e) {
                detach(emitter);
            }
        }
    }
}
