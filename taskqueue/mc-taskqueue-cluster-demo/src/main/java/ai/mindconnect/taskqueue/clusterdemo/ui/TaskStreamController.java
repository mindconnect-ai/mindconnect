package ai.mindconnect.taskqueue.clusterdemo.ui;

import ai.mindconnect.channel.Channel;
import ai.mindconnect.channel.ChannelRegistry;
import ai.mindconnect.channel.Subscription;
import ai.mindconnect.taskqueue.bridge.TaskChannelBridge;
import ai.mindconnect.taskqueue.bridge.TaskEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The live task view over SSE: one connection = one subscription on the
 * {@code tasks} channel. Replay of missed events (seq &gt; lastSeq) happens
 * inside {@code subscribe}, then the subscription continues live — the
 * client's reconnect bridge sends the last SSE id it saw as {@code lastSeq}.
 *
 * <p>The {@code Sui-Stream-Channel} header names the DOM node the patches
 * target ({@code task-board}); the client uses it to re-attach the stream to
 * the page after navigation.
 */
@RestController
public class TaskStreamController {

    private static final Logger log = LoggerFactory.getLogger(TaskStreamController.class);

    private final ChannelRegistry channels;
    private final TasksRenderer board;
    private final ObjectMapper objectMapper;

    public TaskStreamController(ChannelRegistry channels, TasksRenderer board, ObjectMapper objectMapper) {
        this.channels = channels;
        this.board = board;
        this.objectMapper = objectMapper;
    }

    @GetMapping(value = "/tasks/api/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@RequestParam(name = "lastSeq", defaultValue = "0") long lastSeq) {
        Channel<TaskEvent> channel = channels.channel(TaskChannelBridge.ALL_TASKS);
        SseEmitter emitter = new SseEmitter(0L); // no timeout — crawls run minutes

        var subscription = new AtomicReference<Subscription>();
        subscription.set(channel.subscribe(lastSeq, event -> {
            // The response is committed — errors can only travel as events.
            try {
                emitter.send(SseEmitter.event()
                        .id(Long.toString(event.seq()))
                        .name("patch")
                        .data(objectMapper.writeValueAsString(board.patchFor(event))));
            } catch (IOException | RuntimeException e) {
                log.debug("SSE subscriber gone, closing subscription: {}", e.getMessage());
                close(subscription);
                emitter.complete();
            }
        }));

        emitter.onCompletion(() -> close(subscription));
        emitter.onTimeout(() -> close(subscription));
        emitter.onError(t -> close(subscription));

        return ResponseEntity.ok()
                .header("Sui-Stream-Channel", TasksRenderer.BOARD_ID)
                .header("Sui-Stream-Label", "Task events")
                .header("Sui-Stream-Return-Href", "/tasks")
                .body(emitter);
    }

    private static void close(AtomicReference<Subscription> subscription) {
        Subscription sub = subscription.getAndSet(null);
        if (sub != null) {
            sub.close();
        }
    }
}
