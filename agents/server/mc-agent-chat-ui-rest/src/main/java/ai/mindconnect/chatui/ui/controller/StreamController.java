package ai.mindconnect.chatui.ui.controller;

import ai.mindconnect.chatui.service.ActiveStreams;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * Generic API over the {@link ActiveStreams} registry. Three endpoints,
 * channel-id-keyed, JSON-only — no SSE-specific semantics. Any feature
 * that registers a stream in the registry shows up here automatically.
 *
 * <ul>
 *   <li>{@code GET  /admin/api/streams}              — list all currently live streams.</li>
 *   <li>{@code GET  /chat/api/streams/{channelId}}  — single-stream lookup, 404 if not running.</li>
 *   <li>{@code DELETE /chat/api/streams/{channelId}} — cooperatively cancel the named stream.</li>
 * </ul>
 *
 * <p>The chat-page renderer uses the single-stream lookup to decide
 * between rendering the Send and Stop button. UIs that show a global
 * "what's running?" indicator would use the list endpoint.
 */
@RestController
@RequestMapping("/chat/api/streams")
public class StreamController {

    private final ActiveStreams activeStreams;

    @Autowired
    public StreamController(ActiveStreams activeStreams) {
        this.activeStreams = activeStreams;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() {
        var streams = activeStreams.snapshot().stream()
                .map(StreamController::serialize)
                .toList();
        return ResponseEntity.ok(streams);
    }

    @GetMapping("/{channelId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String channelId) {
        return activeStreams.findHandle(channelId)
                .map(h -> ResponseEntity.ok(serialize(h)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Cooperatively cancels the named stream. 204 if a cancel was
     * dispatched (the stream was running and accepted the signal),
     * 404 if no such stream is registered.
     */
    @DeleteMapping("/{channelId}")
    public ResponseEntity<Void> cancel(@PathVariable String channelId) {
        return activeStreams.cancel(channelId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    /**
     * Reconnect endpoint. The client opens a fresh SSE GET on this URL
     * with {@code lastSeq} set to the seq of the last event it processed
     * (or 0 for a clean attach). The server replays every event in the
     * channel's ring buffer with {@code seq > lastSeq} and then subscribes
     * the emitter to the live feed.
     *
     * <p>Use cases:
     * <ul>
     *   <li>The user pressed F5 or closed/reopened a tab — their original
     *       reader is gone, but the server-side run is still going.</li>
     *   <li>A second tab observing the same session wants to see the
     *       turn live.</li>
     * </ul>
     *
     * <p>404 when the stream is no longer registered (already finished).
     */
    @GetMapping(value = "/{channelId}/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> resume(@PathVariable String channelId,
                                             @RequestParam(name = "lastSeq", defaultValue = "0") long lastSeq) {
        var handleOpt = activeStreams.findHandle(channelId);
        if (handleOpt.isEmpty()) return ResponseEntity.notFound().build();
        var handle = handleOpt.get();

        // Long-lived: agent turns can take minutes. The handle's cancel
        // path applies just like for the original POST connection.
        var emitter = new SseEmitter(0L);
        handle.bus().attach(emitter, lastSeq);
        // Drop subscriber when the client goes away — same lifecycle hooks
        // as the original POST, scoped to *this* emitter rather than the
        // channel as a whole (the channel keeps producing while other
        // subscribers stay attached).
        emitter.onCompletion(() -> handle.bus().detach(emitter));
        emitter.onError(t -> handle.bus().detach(emitter));
        emitter.onTimeout(() -> handle.bus().detach(emitter));

        var headers = new HttpHeaders();
        headers.add("Sui-Stream-Channel",    handle.channelId());
        headers.add("Sui-Stream-Return-Href", handle.returnHref());
        headers.add("Sui-Stream-Label",      handle.label());
        return ResponseEntity.ok().headers(headers).body(emitter);
    }

    private static Map<String, Object> serialize(ActiveStreams.Handle h) {
        return Map.of(
                "channelId",  h.channelId(),
                "label",      h.label(),
                "returnHref", h.returnHref(),
                "startedAt",  h.startedAt().toString()
        );
    }
}
