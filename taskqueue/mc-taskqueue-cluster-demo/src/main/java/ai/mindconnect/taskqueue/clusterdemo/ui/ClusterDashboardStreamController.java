package ai.mindconnect.taskqueue.clusterdemo.ui;

import ai.mindconnect.channel.Channel;
import ai.mindconnect.channel.ChannelRegistry;
import ai.mindconnect.channel.Subscription;
import ai.mindconnect.taskqueue.bridge.TaskChannelBridge;
import ai.mindconnect.taskqueue.bridge.TaskEvent;
import ai.mindconnect.taskqueue.clusterdemo.WorkerProcessManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The dashboard's live wire: one SSE connection subscribes to the task
 * channel (seq-stamped, resumable via {@code lastSeq}) AND to worker
 * lifecycle changes (no seq of their own — they ride along without an SSE id,
 * so the reconnect cursor stays the task channel's). Both send the same full
 * dashboard patch; replace-based rendering makes the double-trigger harmless.
 */
@RestController
public class ClusterDashboardStreamController {

    private static final Logger log = LoggerFactory.getLogger(ClusterDashboardStreamController.class);

    /**
     * One connection: the emitter plus the lock that serialises its writes.
     * Two threads feed every connection — the channel's drain thread (task
     * events) and worker lifecycle threads — and {@link SseEmitter} is not
     * thread-safe: a colliding send throws, the stream dies, and the
     * dashboard silently stops being live.
     */
    private record Connection(SseEmitter emitter, Object writeLock) {
        Connection(SseEmitter emitter) {
            this(emitter, new Object());
        }
    }

    private final ChannelRegistry channels;
    private final ClusterDashboardRenderer dashboard;
    private final WorkerProcessManager workers;
    private final ObjectMapper objectMapper;
    private final List<Connection> connections = new CopyOnWriteArrayList<>();

    public ClusterDashboardStreamController(ChannelRegistry channels, ClusterDashboardRenderer dashboard,
                                            WorkerProcessManager workers, ObjectMapper objectMapper) {
        this.channels = channels;
        this.dashboard = dashboard;
        this.workers = workers;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void watchWorkerLifecycle() {
        // Off the caller's thread: a process-exit callback must never wait on
        // a slow viewer's socket.
        workers.onChange(() -> Thread.ofVirtual().start(() -> {
            for (Connection connection : connections) {
                sendPatch(connection, null);
            }
        }));
    }

    @GetMapping(value = "/tasks/api/dashboard-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@RequestParam(name = "lastSeq", defaultValue = "0") long lastSeq) {
        Channel<TaskEvent> channel = channels.channel(TaskChannelBridge.ALL_TASKS);
        Connection connection = new Connection(new SseEmitter(0L));
        SseEmitter emitter = connection.emitter();

        Subscription subscription = channel.subscribe(lastSeq, event -> sendPatch(connection, event.seq()));
        connections.add(connection);
        Runnable close = () -> {
            subscription.close();
            connections.remove(connection);
        };
        emitter.onCompletion(close);
        emitter.onTimeout(close);
        emitter.onError(t -> close.run());

        return ResponseEntity.ok()
                .header("Sui-Stream-Channel", ClusterDashboardRenderer.DASHBOARD_ID)
                .header("Sui-Stream-Label", "Cluster events")
                .header("Sui-Stream-Return-Href", "/tasks/dashboard")
                .body(emitter);
    }

    private void sendPatch(Connection connection, Long seq) {
        // Rendering touches the database — a transient store hiccup is OUR
        // problem, not the viewer's. Only a failed SEND means the subscriber
        // is gone; a failed render skips this event and the next one heals
        // the view, because every patch is a full re-render.
        String json;
        try {
            json = objectMapper.writeValueAsString(dashboard.patch());
        } catch (RuntimeException | IOException e) {
            log.warn("Dashboard patch render failed, keeping the stream: {}", e.getMessage());
            return;
        }
        try {
            SseEmitter.SseEventBuilder event = SseEmitter.event().name("patch").data(json);
            if (seq != null) {
                event = event.id(Long.toString(seq));
            }
            synchronized (connection.writeLock()) {
                connection.emitter().send(event);
            }
        } catch (IOException | RuntimeException e) {
            log.debug("Dashboard SSE subscriber gone: {}", e.getMessage());
            connections.remove(connection);
            connection.emitter().complete();
        }
    }
}
