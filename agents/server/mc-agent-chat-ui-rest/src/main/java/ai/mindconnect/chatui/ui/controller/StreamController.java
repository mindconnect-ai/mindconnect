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

    private final ai.mindconnect.chatui.service.SessionStreams sessionStreams;

    @Autowired
    public StreamController(ActiveStreams activeStreams,
                            ai.mindconnect.chatui.service.SessionStreams sessionStreams) {
        this.activeStreams = activeStreams;
        this.sessionStreams = sessionStreams;
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
    public ResponseEntity<SseEmitter> resume(
            @PathVariable String channelId,
            @RequestParam(name = "lastSeq", defaultValue = "0") long lastSeq,
            @RequestParam(name = "from", required = false) Long from) {
        // `from` wins over `lastSeq`. The page renderer knows exactly what
        // the page it just rendered already shows and puts that position in
        // the resume URL; the client appends its own lastSeq=0 blindly, and
        // honouring that would replay APPEND patches the page already has.
        long cursor = from != null ? from : lastSeq;

        // No 404 when nothing is running. The stream belongs to the SESSION,
        // and a client attaches precisely so that it is already listening
        // when a turn starts — turning it away while the session is quiet
        // would defeat the purpose.
        var bus = sessionStreams.bus(channelId);
        var handleOpt = activeStreams.findHandle(channelId);

        // Long-lived: agent turns can take minutes, and the connection
        // outlives them. Idle time is covered by the heartbeat.
        var emitter = new SseEmitter(0L);

        // A client that arrives mid-turn has no streaming bubble in its DOM,
        // so every cumulative token REPLACE would land nowhere. The catch-up
        // frames create it and fill it with the reply so far.
        var prelude = new java.util.ArrayList<ai.mindconnect.chatui.service.StreamBus.Event>();
        sessionStreams.catchUp(channelId).ifPresent(c -> {
            if (c.bubblePatch() != null) {
                prelude.add(new ai.mindconnect.chatui.service.StreamBus.Event(0, "patch", c.bubblePatch()));
            }
            if (c.textPatch() != null) {
                prelude.add(new ai.mindconnect.chatui.service.StreamBus.Event(0, "patch", c.textPatch()));
            }
        });
        bus.attach(emitter, cursor, prelude);

        emitter.onCompletion(() -> bus.detach(emitter));
        emitter.onError(t -> bus.detach(emitter));
        emitter.onTimeout(() -> bus.detach(emitter));

        var headers = new HttpHeaders();
        headers.add("Sui-Stream-Channel", channelId);
        headers.add("Sui-Stream-Return-Href",
                handleOpt.map(ActiveStreams.Handle::returnHref).orElse(""));
        headers.add("Sui-Stream-Label",
                handleOpt.map(ActiveStreams.Handle::label).orElse("Agent"));
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
