package ai.mindconnect.agent.responses.controller;

import ai.mindconnect.agent.protocol.Response;
import ai.mindconnect.agent.protocol.Session;
import ai.mindconnect.agent.protocol.api.ResponseRequest;
import ai.mindconnect.agent.protocol.api.SubscribeRequest;
import ai.mindconnect.agent.protocol.runtime.AgentRuntimeBackend;
import ai.mindconnect.agent.responses.ModelResolver;
import ai.mindconnect.agent.responses.ResponsesMapper;
import ai.mindconnect.agent.responses.SessionBinder;
import ai.mindconnect.agent.responses.StreamEvents;
import ai.mindconnect.agent.responses.wire.CreateResponseRequest;
import ai.mindconnect.agent.responses.wire.ResponseDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The OpenAI Responses API, served by the Mindconnect runtime.
 *
 * <p>Point an OpenAI client's {@code base_url} here and it works unchanged:
 * the wire format is theirs, the agent behind it is ours. What the client
 * calls a model is an agent or an llm-config (see {@link ModelResolver}), and
 * what it calls a conversation is a session.
 *
 * <p>This class translates and delegates; it holds no agent logic. The
 * protocol surface it calls is the same one the OpenAI backend implements in
 * the other direction, which is what makes the two interchangeable.
 */
@RestController
@RequestMapping("/v1")
public class ResponsesController {

    private static final Logger log = LoggerFactory.getLogger(ResponsesController.class);

    private final AgentRuntimeBackend backend;
    private final ModelResolver models;
    private final SessionBinder sessions;
    private final ResponsesMapper mapper;
    private final ObjectMapper json;

    public ResponsesController(AgentRuntimeBackend backend, ModelResolver models,
                               SessionBinder sessions, ResponsesMapper mapper, ObjectMapper json) {
        this.backend = backend;
        this.models = models;
        this.sessions = sessions;
        this.mapper = mapper;
        this.json = json;
    }

    /**
     * One route for both forms. The body decides, not the {@code Accept}
     * header: an OpenAI client asks for a stream by sending
     * {@code "stream": true} and keeps asking for {@code application/json},
     * so splitting the mapping by produced type would make the streaming
     * call unreachable from the official SDKs.
     */
    @PostMapping("/responses")
    public ResponseEntity<?> create(@RequestBody CreateResponseRequest request) {
        if (request.streaming()) {
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(stream(request));
        }
        return ResponseEntity.ok(run(request));
    }

    private SseEmitter stream(CreateResponseRequest request) {
        SseEmitter emitter = new SseEmitter(0L);        // a turn can take minutes

        ModelResolver.Resolution resolution = models.resolve(request.model());
        Session session = sessions.bind(request, resolution);

        // The response object every lifecycle frame repeats. It is filled in
        // as soon as the run exists and re-read on each frame, so a later
        // frame carries the later state.
        AtomicReference<ResponseDto> current = new AtomicReference<>();
        StreamEvents events = new StreamEvents(current::get, mapper);

        Response created = backend.responses().create(new ResponseRequest(
                session.id(), mapper.toItems(request.input()), true, java.util.List.of()));
        current.set(mapper.toDto(created, request.model()));

        var subscription = backend.responses().subscribe(
                SubscribeRequest.replay(created.id()),
                event -> {
                    // Refresh before writing: a lifecycle frame must not
                    // announce "completed" while carrying the queued object.
                    backend.responses().get(created.id())
                            .ifPresent(r -> current.set(mapper.toDto(r, request.model())));
                    StreamEvents.Frame frame = events.frameFor(event);
                    if (frame == null) {
                        return;
                    }
                    try {
                        emitter.send(SseEmitter.event().name(frame.event()).data(frame.data()));
                    } catch (Exception e) {
                        // The client hung up. Nothing to recover: the run
                        // continues and its result stays retrievable by id.
                        log.debug("Responses stream {} dropped: {}", created.id(), e.toString());
                        return;
                    }
                    if (StreamEvents.isTerminal(event)) {
                        // The run is over, so the stream is too. A client
                        // reads until the connection closes; holding it open
                        // makes a finished response look like a thinking one.
                        try {
                            emitter.complete();
                        } catch (Exception ignore) {
                            // already gone
                        }
                    }
                });

        emitter.onCompletion(subscription::close);
        emitter.onError(t -> subscription.close());
        emitter.onTimeout(subscription::close);
        return emitter;
    }

    @GetMapping(value = "/responses/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> get(@PathVariable String id) {
        return backend.responses().get(id)
                .<ResponseEntity<?>>map(r -> ResponseEntity.ok(mapper.toDto(r, r.agentName())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(error("not_found", "No response with id '" + id + "'.")));
    }

    @PostMapping(value = "/responses/{id}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> cancel(@PathVariable String id) {
        if (!backend.responses().cancel(id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(error("not_found", "No cancellable response with id '" + id + "'."));
        }
        return get(id);
    }

    private ResponseDto run(CreateResponseRequest request) {
        ModelResolver.Resolution resolution = models.resolve(request.model());
        Session session = sessions.bind(request, resolution);
        Response response = backend.responses().create(new ResponseRequest(
                session.id(), mapper.toItems(request.input()),
                request.detached(), java.util.List.of()));
        return mapper.toDto(response, request.model());
    }

    static Map<String, Object> error(String type, String message) {
        return Map.of("error", Map.of("type", type, "message", message));
    }
}
