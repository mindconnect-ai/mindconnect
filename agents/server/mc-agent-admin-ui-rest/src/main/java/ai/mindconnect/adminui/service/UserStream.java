package ai.mindconnect.adminui.service;

import ai.mindconnect.adminui.ui.component.TaskMonitorComponent;
import ai.mindconnect.agent.service.stream.UserChannels;
import ai.mindconnect.agentrest.dto.UserEventFrame;
import ai.mindconnect.channel.Subscription;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The one stream every admin page keeps attached: the user's own feed. Two
 * sources share the connection —
 * <ul>
 *   <li>the {@link TaskMonitor}'s board, as {@code patch} frames that keep
 *       the header badge and the task dialog current, rendered for the user
 *       on this connection (whether a Cancel appears depends on who looks);</li>
 *   <li>the user's {@link UserChannels} stream, as {@code user} frames — a
 *       session opened or titled, a turn started or finished, a tool waiting
 *       for an answer — so a tab learns what happens in the sessions it is
 *       not looking at.</li>
 * </ul>
 *
 * <p>One connection rather than one per source because a browser allows six
 * per host, shared by all its tabs: every stream a page holds is a slot a
 * second tab cannot have. The task badge used to be a stream of its own.
 *
 * <p>Live only. The page that attaches already shows the current board and
 * the current session list; what it needs is what changes from here on.
 * The heartbeat is also the liveness check: a write that fails drops that
 * subscriber.
 */
@Component
public class UserStream {

    private static final Logger log = LoggerFactory.getLogger(UserStream.class);

    /**
     * The stream's channel id AND the DOM id of the header element the SPA
     * looks for: it keeps a stream attached only while an element with the
     * channel's id is mounted. {@code AdminLayout} renders that element on
     * every page, so the stream survives navigation and is opened once.
     */
    public static final String CHANNEL_ID = "user-stream";
    public static final String STREAM_URL = "/admin/api/stream";

    /** SSE comment keeping idle connections alive through proxies. */
    private static final long HEARTBEAT_SECONDS = 20;

    private record Attached(Subscription tasks, Subscription events) {
        void close() {
            if (tasks != null) tasks.close();
            events.close();
        }
    }

    /** Absent when the host runs no task queue; the stream then carries user events only. */
    private final TaskMonitor taskMonitor;
    private final UserChannels userChannels;
    private final ObjectMapper objectMapper;
    private final Map<SseEmitter, Attached> attached = new ConcurrentHashMap<>();
    private final ScheduledExecutorService pulse = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "user-stream-heartbeat");
        t.setDaemon(true);
        return t;
    });

    public UserStream(Optional<TaskMonitor> taskMonitor, UserChannels userChannels, ObjectMapper objectMapper) {
        this.taskMonitor = taskMonitor.orElse(null);
        this.userChannels = userChannels;
        this.objectMapper = objectMapper;
        pulse.scheduleWithFixedDelay(this::heartbeat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    @PreDestroy
    void shutdown() {
        pulse.shutdownNow();
        for (var entry : attached.entrySet()) {
            entry.getValue().close();
            try {
                entry.getKey().complete();
            } catch (Exception ignore) {
                // already gone
            }
        }
        attached.clear();
    }

    /**
     * Subscribes a browser tab for {@code userId}. The first thing written
     * is a comment: Spring commits an SseEmitter's headers with its first
     * write, and until then the browser's {@code fetch()} has not resolved
     * — the SPA does not know it holds this connection and a second page
     * render opens another. On a quiet server the first real frame could
     * be the heartbeat, 20 seconds away.
     */
    public void attach(SseEmitter emitter, String userId) {
        try {
            emitter.send(SseEmitter.event().comment("attached"));
        } catch (Exception e) {
            log.debug("User stream for {} could not be opened: {}", userId, e.toString());
            return;
        }
        Subscription tasks = taskMonitor == null ? null : taskMonitor.subscribe(event -> {
            try {
                emitter.send(SseEmitter.event()
                        .id(Long.toString(event.seq()))
                        .name("patch")
                        .data(json(TaskMonitorComponent.livePatch(event.value(), userId))));
            } catch (Exception e) {
                detach(emitter);
            }
        });
        Subscription events = userChannels.subscribe(userId, userChannels.lastSeq(userId), event -> {
            try {
                emitter.send(SseEmitter.event()
                        .name("user")
                        .data(json(UserEventFrame.of(event.seq(), event.value()))));
            } catch (Exception e) {
                detach(emitter);
            }
        });
        Attached previous = attached.put(emitter, new Attached(tasks, events));
        if (previous != null) previous.close();
        log.debug("User stream attached for {} — {} open", userId, attached.size());
    }

    public void detach(SseEmitter emitter) {
        Attached subscriptions = attached.remove(emitter);
        if (subscriptions != null) {
            subscriptions.close();
            log.debug("User stream detached — {} open", attached.size());
        }
    }

    /** How many tabs are attached right now — for tests and logs. */
    public int subscriberCount() {
        return attached.size();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Keeps idle connections open and notices the ones that are gone. */
    private void heartbeat() {
        for (SseEmitter emitter : attached.keySet()) {
            try {
                emitter.send(SseEmitter.event().comment("hb"));
            } catch (Exception e) {
                detach(emitter);
            }
        }
    }
}
