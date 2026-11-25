package ai.mindconnect.agentrest.controller;

import ai.mindconnect.agentrest.dto.WorkingMemoryResponse;
import ai.mindconnect.agent.runtime.capabilities.memory.MemoryInspection;
import ai.mindconnect.agent.management.api.AgentManagement;
import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.management.api.ManagedSession;
import ai.mindconnect.agent.management.api.SessionDirectories;
import ai.mindconnect.agent.management.directory.WorkingDirPolicy;
import ai.mindconnect.agent.management.tool.AgentToolBinding;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.Interruption;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.domain.ToolBinding;
import ai.mindconnect.agent.runtime.capabilities.review.ResponseReviewPlugin;
import ai.mindconnect.agent.runtime.capabilities.skill.SkillPlugin;
import ai.mindconnect.agent.runtime.capabilities.subagent.SubAgentPlugin;
import ai.mindconnect.agentrest.dto.SessionMessageResponse;
import ai.mindconnect.agentrest.dto.SessionResponse;
import ai.mindconnect.agentrest.dto.ToolApproval;
import ai.mindconnect.agentrest.auth.CurrentUser;
import ai.mindconnect.agentrest.auth.SessionAccess;
import ai.mindconnect.agentrest.dto.CreateAgentRequest;
import ai.mindconnect.agentrest.dto.StartSessionRequest;
import ai.mindconnect.agentrest.dto.AttachedFrame;
import ai.mindconnect.agentrest.dto.UserEventFrame;
import ai.mindconnect.agentrest.dto.IncompleteFrame;
import ai.mindconnect.agentrest.stream.TurnFrame;
import ai.mindconnect.agentrest.dto.UpdateToolsRequest;
import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.filestore.FileId;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * External REST API for agents and their chat sessions: agent CRUD, session
 * lifecycle, chat streaming, history and working memory. Delegates to the
 * same services the admin UI runs on: the runtime's agent and session
 * management.
 *
 * <p>Sessions belong to the authenticated caller ({@link CurrentUser}): one is
 * opened for the caller and listed for the caller, and every
 * {@code /sessions/{sessionId}/…} endpoint asks {@link SessionAccess} before
 * it does anything — someone else's session answers 404, exactly like one
 * that does not exist. Agents are operator configuration and stay open to
 * every authenticated caller.
 */
@RestController
@RequestMapping("/api")
public class AgentApiController {

    private static final Logger log = LoggerFactory.getLogger(AgentApiController.class);

    private static final int DEFAULT_MAX_ROUNDS = 10;

    /** How long a finished turn's stream waits for its Done frame before it closes anyway. */

    private final WorkingDirPolicy workingDirPolicy;
    private final ai.mindconnect.agentrest.service.SessionTurns sessionTurns;
    private final ai.mindconnect.agentrest.service.TurnFrameStream frameStream;
    private final ai.mindconnect.agent.management.api.SessionManagement management;
    private final ai.mindconnect.agent.runtime.capabilities.memory.MemoryCompaction compaction;
    /** Asked for what only some assemblies have: a service is looked up when it is needed. */
    private final ai.mindconnect.agent.sdk.AgentRuntime runtime;
    private final ai.mindconnect.agentrest.service.ChatOpening chatOpener;
    private final ai.mindconnect.filestore.FileStore fileStore;
    private final SessionAccess sessionAccess;
    private final ObjectMapper compactMapper;
    private final ai.mindconnect.agent.management.directory.WorkingDirBrowser dirBrowser;

    public AgentApiController(WorkingDirPolicy workingDirPolicy,
                            ai.mindconnect.agentrest.service.SessionTurns sessionTurns,
                            ai.mindconnect.agentrest.service.TurnFrameStream frameStream,
                            ai.mindconnect.agent.sdk.AgentRuntime runtime,
                            ai.mindconnect.agent.runtime.capabilities.memory.MemoryCompaction compaction,
                            ai.mindconnect.agentrest.service.ChatOpening chatOpener,
                            ai.mindconnect.filestore.FileStore fileStore,
                            SessionAccess sessionAccess,
                            ObjectMapper objectMapper,
                            @org.springframework.lang.Nullable
                            ai.mindconnect.agent.management.directory.WorkingDirBrowser dirBrowser) {
        // A host that defines no browser gets one over the directory
        // policy — the same tree, without a bean to declare.
        this.dirBrowser = dirBrowser != null ? dirBrowser
                : new ai.mindconnect.agent.management.directory.WorkingDirBrowser(workingDirPolicy);
        this.workingDirPolicy = workingDirPolicy;
        this.sessionTurns = sessionTurns;
        this.frameStream = frameStream;
        this.management = runtime.service(ai.mindconnect.agent.management.api.SessionManagement.class);
        this.compaction = compaction;
        this.runtime = runtime;
        this.chatOpener = chatOpener;
        this.fileStore = fileStore;
        this.sessionAccess = sessionAccess;
        this.compactMapper = objectMapper.copy().disable(
                com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);
    }

    /** A request that names an unknown file or part kind is the caller's mistake: 400, not 500. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage() == null ? "bad request" : e.getMessage()));
    }

    // ── Agent CRUD ──────────────────────────────────────────────────────────

    @Operation(tags = "Agents", summary = "Create an agent",
            description = "Creates an agent; it starts with no tools.")
    @PostMapping("/agents")
    public ManagedAgent createAgent(@RequestBody CreateAgentRequest req) {
        log.info("POST /api/agents name={}", req.name());
        ManagedAgent agent = agents().create(ManagedAgent.of(new AgentDefinition(req.name(),
                        req.description() == null ? "" : req.description(),
                        req.systemPrompt() == null ? "" : req.systemPrompt(),
                        req.llmConfigName() == null ? ModelSettings.NONE : ModelSettings.ofModel(req.llmConfigName()),
                        List.of(), DEFAULT_MAX_ROUNDS, Map.of()))
                .shownAs(null, null, req.welcomeMessage()));
        log.info("Created agent: {} ({})", agent.definition().name(), agent.id());
        return agent;
    }

    /** How an agent's skills are picked: {@code ALL}, {@code NONE} or {@code SPECIFIC} with the names. */
    public record SkillsSetting(String mode, List<String> names) { }

    /**
     * Partial update: absent (null) fields keep their current value. {@code version},
     * when given, is the agent's version as read: the update is refused with 409 if the
     * agent was saved since.
     */
    public record UpdateAgentRequest(String name, String description, String systemPrompt,
                                     String welcomeMessage, String llmConfigName,
                                     Integer maxIterations, List<String> responseReviewers,
                                     List<String> callableAgents, SkillsSetting skills, Long version) { }

    @Operation(tags = "Agents", summary = "Update an agent",
            description = "Partial update — absent (null) fields keep their current value. "
                    + "Covers the same fields as the admin UI's edit form. Send the agent's "
                    + "`version` as you read it to have the update refused with 409 when the "
                    + "agent was saved since; without it the update is applied to the agent as stored.")
    @PutMapping("/agents/{agentId}")
    public ManagedAgent updateAgent(@PathVariable String agentId,
                                    @RequestBody UpdateAgentRequest req) {
        log.info("PUT /api/agents/{}", agentId);
        return changeAgent(agentId, req.version(), stored -> {
            AgentDefinition definition = stored.definition();
            AgentDefinition settings = definition;
            if (req.responseReviewers() != null) {
                settings = ResponseReviewPlugin.withReviewers(settings, req.responseReviewers());
            }
            if (req.callableAgents() != null) {
                settings = SubAgentPlugin.withSubAgents(settings, req.callableAgents());
            }
            if (req.skills() != null) {
                settings = switch (req.skills().mode() == null ? "ALL"
                        : req.skills().mode().toUpperCase(java.util.Locale.ROOT)) {
                    case "NONE" -> SkillPlugin.withSkills(settings, List.of());
                    case "SPECIFIC" -> SkillPlugin.withSkills(settings,
                            req.skills().names() == null ? List.of() : req.skills().names());
                    case "ALL" -> SkillPlugin.withEverySkill(settings);
                    default -> throw new IllegalArgumentException("There is no skills mode " + req.skills().mode());
                };
            }
            return stored.withDefinition(new AgentDefinition(
                            req.name() != null ? req.name() : definition.name(),
                            req.description() != null ? req.description() : definition.description(),
                            req.systemPrompt() != null ? req.systemPrompt() : definition.instructions(),
                            req.llmConfigName() != null ? ModelSettings.ofModel(req.llmConfigName())
                                    : definition.modelSettings(),
                            definition.tools(),
                            req.maxIterations() != null ? req.maxIterations() : definition.maxRounds(),
                            settings.metadata()))
                    .shownAs(stored.group(), stored.icon(),
                            req.welcomeMessage() != null ? req.welcomeMessage() : stored.welcomeMessage());
        });
    }

    @Operation(tags = "Agents", summary = "Delete an agent")
    @DeleteMapping("/agents/{agentId}")
    public ResponseEntity<Void> deleteAgent(@PathVariable String agentId) {
        log.info("DELETE /api/agents/{}", agentId);
        AgentId id = AgentId.of(agentId);
        if (agents().find(id).isEmpty()) return ResponseEntity.notFound().build();
        agents().delete(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(tags = "Agents", summary = "Duplicate an agent",
            description = "Creates \"{name}-copy\" with a fresh id and the same tools.")
    @PostMapping("/agents/{agentId}/copy")
    public ResponseEntity<ManagedAgent> copyAgent(@PathVariable String agentId) {
        log.info("POST /api/agents/{}/copy", agentId);
        AgentId id = AgentId.of(agentId);
        if (agents().find(id).isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(agents().copy(id));
    }

    @Operation(tags = "Agents", summary = "Replace an agent's tools",
            description = "Sets the complete tool list — tools not in the request are removed.")
    @PutMapping("/agents/{agentId}/tools")
    public ManagedAgent updateTools(@PathVariable String agentId,
                                    @RequestBody UpdateToolsRequest req) {
        log.info("PUT /api/agents/{}/tools count={}", agentId, req.tools().size());
        // A tool sent switched off is one the agent does not have.
        List<ToolBinding> tools = req.tools().stream().map(tool -> tool.toTool())
                .filter(ai.mindconnect.agent.tool.AgentTool::enabled).map(AgentToolBinding::of).toList();
        return changeAgent(agentId, null, stored -> {
            AgentDefinition definition = stored.definition();
            return stored.withDefinition(new AgentDefinition(definition.name(), definition.description(),
                    definition.instructions(), definition.modelSettings(), tools, definition.maxRounds(),
                    definition.metadata()));
        });
    }

    @Operation(tags = "Agents", summary = "List agents")
    @GetMapping("/agents")
    public List<ManagedAgent> listAgents() {
        log.info("GET /api/agents");
        return agents().list();
    }

    @Operation(tags = "Agents", summary = "Get an agent")
    @GetMapping("/agents/{agentId}")
    public ResponseEntity<ManagedAgent> findAgent(@PathVariable String agentId) {
        log.info("GET /api/agents/{}", agentId);
        return agents().find(AgentId.of(agentId))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    private AgentManagement agents() {
        return runtime.service(AgentManagement.class);
    }

    /** @param version the agent's version as the caller read it; {@code null} applies to the agent as stored */
    private ManagedAgent changeAgent(String agentId, Long version,
                                     java.util.function.UnaryOperator<ManagedAgent> change) {
        AgentId id = AgentId.of(agentId);
        long expected = version != null ? version : agents().find(id)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "No such agent")).version();
        return agents().update(id, expected, change);
    }

    /** An agent saved since the caller read it: 409, so the caller reads again instead of overwriting. */
    @ExceptionHandler(java.util.ConcurrentModificationException.class)
    public ResponseEntity<Map<String, String>> conflict(java.util.ConcurrentModificationException e) {
        return ResponseEntity.status(org.springframework.http.HttpStatus.CONFLICT)
                .body(Map.of("error", e.getMessage() == null ? "conflict" : e.getMessage()));
    }

    // ── Sessions ────────────────────────────────────────────────────────────

    @Operation(tags = "Sessions", summary = "Start a chat session",
            description = "Opens a new session for the agent, owned by the authenticated caller; "
                    + "the returned id addresses chat, history, memory and file endpoints. Body "
                    + "{agentId} — a userId still sent by an older client is ignored.")
    @PostMapping("/sessions")
    public SessionResponse startSession(@RequestBody StartSessionRequest req, @CurrentUser UserId caller) {
        log.info("POST /api/sessions agentId={} user={} workingDir={}",
                req.agentId(), caller, req.workingDir());
        ManagedSession opened = chatOpener.open(
                AgentId.of(req.agentId()), caller, null, req.workingDir(), req.additionalDirs());
        log.info("Session started: {}", opened.id().value());
        return answered(opened);
    }

    @Operation(tags = "Sessions", summary = "List the directories the caller may work in",
            description = "One level of the server's tree under `mindconnect.tools.working-dir-root` "
                    + "(the caller's own root when it carries `{user}`): the directory at `path`, its "
                    + "parent, its sub-directories. Without `path`, the root. 400 when `path` lies "
                    + "outside the root or is no directory.")
    @GetMapping("/directories")
    public ai.mindconnect.agent.management.directory.WorkingDirBrowser.Listing listDirectories(
            @RequestParam(required = false) String path, @CurrentUser UserId caller) {
        return dirBrowser.list(caller.value(), path);
    }

    @Operation(tags = "Sessions", summary = "Change a session's working directory",
            description = "Moves the session to another directory: the file tools' base directory, "
                    + "named in the prompt from the next turn on. Body `{\"workingDir\": \"/path\", "
                    + "\"additionalDirs\": [\"/other\"]}`; a null or blank workingDir clears it, an absent "
                    + "additionalDirs keeps the current ones, an empty list clears them. 400 when a "
                    + "directory does not exist or lies outside `mindconnect.tools.working-dir-root`; "
                    + "404 when the session is not the caller's.")
    @PutMapping("/sessions/{sessionId}/working-dir")
    public SessionResponse changeWorkingDir(@PathVariable String sessionId,
                                         @RequestBody Map<String, Object> body,
                                         @CurrentUser UserId caller) {
        SessionId id = owned(sessionId, caller);
        Object dir = body == null ? null : body.get("workingDir");
        String workingDir = dir == null ? null : dir.toString();
        List<String> additionalDirs = null;
        if (body != null && body.get("additionalDirs") instanceof List<?> raw) {
            additionalDirs = raw.stream().filter(java.util.Objects::nonNull).map(Object::toString).toList();
        }
        log.info("PUT /api/sessions/{}/working-dir workingDir={} additionalDirs={}", id, workingDir, additionalDirs);
        // Clearing counts as a choice too: it would take the chat out of its own directory.
        workingDirPolicy.requireChoice();
        ManagedSession current = management.find(id).orElseThrow();
        return answered(management.changeDirectories(current.id(), new SessionDirectories(
                workingDir == null || workingDir.isBlank() ? null : workingDir,
                additionalDirs == null ? current.directories().additional() : additionalDirs)));
    }

    /** The session with the turn underway in it: what a list of sessions shows as busy or waiting. */
    private SessionResponse answered(ManagedSession session) {
        return SessionResponse.of(session, runtime.find(ai.mindconnect.agent.runtime.api.port.in.TurnService.class)
                .flatMap(turns -> turns.runningTurn(session.id())));
    }

    @Operation(tags = "Sessions", summary = "List the caller's sessions for an agent")
    @GetMapping("/sessions")
    public List<SessionResponse> listSessions(@RequestParam String agentId, @CurrentUser UserId caller) {
        log.info("GET /api/sessions agentId={} user={}", agentId, caller);
        List<SessionResponse> sessions = management
                .list(caller).stream()
                .filter(session -> session.agentId().map(id -> id.value().equals(agentId)).orElse(false))
                .map(this::answered).toList();
        log.info("Found {} session(s)", sessions.size());
        return sessions;
    }

    // ── Chat ────────────────────────────────────────────────────────────────

    @Operation(tags = "Sessions", summary = "Send a chat message (SSE stream)",
            description = "Submits the message (plain-text body) and streams the turn as "
                    + "Server-Sent Events: token deltas, tool calls, task updates, then a final "
                    + "Done frame. The stream closes when the turn completes or fails.")
    @PostMapping(value = "/sessions/{sessionId}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streaming(@PathVariable String sessionId, @RequestBody String message,
                                @CurrentUser UserId caller) {
        SessionId id = owned(sessionId, caller);
        log.info("POST /api/sessions/{}/chat message=\"{}\"", sessionId,
                message.length() > 80 ? message.substring(0, 80) + "…" : message);
        return streamTurn(id, ai.mindconnect.message.domain.ContentPart.text(message));
    }

    /**
     * The JSON twin: the text plus files sent with it — uploaded beforehand
     * through {@code POST /api/files}, referenced by id. An image goes to the
     * model as an image part (a vision model sees it with the question), a
     * file as a document part; what a model does not read stands in as a
     * placeholder line.
     */
    @Operation(tags = "Sessions", summary = "Send a chat message with files (SSE stream)",
            description = "JSON body {message, parts:[{kind:image|file, fileId}]}. The files were "
                    + "uploaded through POST /api/files; the message carries them as content "
                    + "parts — an image is sent to a vision model as the picture, a PDF to a "
                    + "document-reading model as the document. Streams the turn as Server-Sent "
                    + "Events like the plain-text variant.")
    @PostMapping(value = "/sessions/{sessionId}/chat", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamingWithParts(@PathVariable String sessionId,
                                         @RequestBody ai.mindconnect.agentrest.dto.ChatRequest request,
                                         @CurrentUser UserId caller) {
        SessionId id = owned(sessionId, caller);
        String message = request.message() == null ? "" : request.message();
        log.info("POST /api/sessions/{}/chat (json) message=\"{}\" parts={}", sessionId,
                message.length() > 80 ? message.substring(0, 80) + "…" : message,
                request.parts() == null ? 0 : request.parts().size());
        List<ai.mindconnect.message.domain.ContentPart> parts = new java.util.ArrayList<>();
        parts.add(new ai.mindconnect.message.domain.ContentPart.Text(message));
        for (var part : request.parts() == null ? List.<ai.mindconnect.agentrest.dto.ChatRequest.Part>of()
                : request.parts()) {
            parts.add(toPart(part, caller));
        }
        if (message.isBlank() && parts.size() == 1) {
            throw new IllegalArgumentException("A chat message needs text or at least one part");
        }
        return streamTurn(id, parts);
    }

    /**
     * A requested part as a domain part — the file's name, type and size from
     * the store. A file the caller may not read is unknown here, like an id
     * that was never uploaded: sending it to the model would hand its content
     * to the caller.
     */
    private ai.mindconnect.message.domain.ContentPart toPart(ai.mindconnect.agentrest.dto.ChatRequest.Part part,
                                                              UserId caller) {
        if (part.fileId() == null || part.fileId().isBlank()) {
            throw new IllegalArgumentException("A part needs a fileId — upload via POST /api/files first");
        }
        ai.mindconnect.filestore.StoredFile stored = fileStore.find(FileId.of(part.fileId()))
                .filter(file -> file.readableBy(caller))
                .orElseThrow(() -> new IllegalArgumentException("Unknown fileId '" + part.fileId()
                        + "' — upload via POST /api/files first"));
        String kind = part.kind() == null ? "" : part.kind().toLowerCase(java.util.Locale.ROOT);
        return switch (kind) {
            case "image" -> new ai.mindconnect.message.domain.ContentPart.Image(
                    stored.id().value(), stored.name(), stored.contentType(), stored.size());
            case "file", "document" -> new ai.mindconnect.message.domain.ContentPart.File(
                    stored.id().value(), stored.name(), stored.contentType(), stored.size());
            default -> throw new IllegalArgumentException(
                    "Unknown part kind '" + part.kind() + "' — use image or file");
        };
    }

    /**
     * Starts the turn and streams it as Server-Sent Events until it ends. A turn
     * that waits at the approval gate announces it with an {@code incomplete}
     * frame; the stream stays open and carries the turn on once somebody answers.
     */
    private SseEmitter streamTurn(SessionId sessionId, List<ai.mindconnect.message.domain.ContentPart> parts) {
        SseEmitter emitter = new SseEmitter(120_000L);
        frameStream.stream(sessionId, () -> Optional.of(sessionTurns.submit(sessionId,
                parts.stream().map(AgentApiController::part).toList())), false, sinkTo(emitter, sessionId));
        return emitter;
    }

    /** The emitter as where a turn's stream goes; a turn that asks is told with what it waits for. */
    private ai.mindconnect.agentrest.service.TurnFrameStream.Sink sinkTo(SseEmitter emitter, SessionId sessionId) {
        return new ai.mindconnect.agentrest.service.TurnFrameStream.Sink() {
            @Override
            public void frame(TurnFrame frame) {
                sendFrame(emitter, frame);
            }

            @Override
            public void asked(ai.mindconnect.agent.runtime.api.domain.TurnId turnId) {
                sendFrame(emitter, IncompleteFrame.of(turnId.value(), openApprovals(sessionId)));
            }

            @Override
            public void ended(String error) {
                endStream(emitter, sessionId, error == null ? null : new IllegalStateException(error));
            }
        };
    }

    /** A part of a message as the runtime takes it. */
    private static ai.mindconnect.agent.loop.domain.ContentPart part(ai.mindconnect.message.domain.ContentPart part) {
        return switch (part) {
            case ai.mindconnect.message.domain.ContentPart.Text text ->
                    new ai.mindconnect.agent.loop.domain.ContentPart.Text(text.text());
            case ai.mindconnect.message.domain.ContentPart.Image image ->
                    new ai.mindconnect.agent.loop.domain.ContentPart.Image(
                            new ai.mindconnect.agent.loop.domain.ContentPart.MediaSource.FileId(image.fileId()),
                            ai.mindconnect.agent.loop.domain.ContentPart.Image.Detail.AUTO, image.name());
            case ai.mindconnect.message.domain.ContentPart.File file ->
                    new ai.mindconnect.agent.loop.domain.ContentPart.Document(
                            new ai.mindconnect.agent.loop.domain.ContentPart.MediaSource.FileId(file.fileId()),
                            file.name());
        };
    }

    /** One frame as JSON; a stream that broke is closed with the failure. */
    private void sendFrame(SseEmitter emitter, Object frame) {
        try {
            emitter.send(SseEmitter.event().data(compactMapper.writeValueAsString(frame),
                    MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }

    private void endStream(SseEmitter emitter, SessionId sessionId, Throwable error) {
        if (error != null) {
            Throwable cause = (error.getCause() != null) ? error.getCause() : error;
            log.error("Chat error for session {}: {}", sessionId, cause.getMessage());
            try {
                emitter.send(SseEmitter.event().data(
                        compactMapper.writeValueAsString(TurnFrame.error(cause.getMessage())),
                        MediaType.APPLICATION_JSON));
            } catch (Exception ignored) {}
            emitter.completeWithError(cause);
        } else {
            log.info("Chat complete for session {}", sessionId);
            emitter.complete();
        }
    }

    @Operation(tags = "Sessions", summary = "Attach to the caller's event stream",
            description = "The path names the caller — their own user id or 'me'; any other user "
                    + "answers 404. What changes about the caller's sessions, across all of them: "
                    + "session_started, session_titled, session_status and session_deleted — what a "
                    + "session list or a notification needs, without attaching to any session. The "
                    + "first frame is {type:'attached'}; the stream tells what changes from then on, "
                    + "and a client that was away reads the list of sessions anew. What a turn says "
                    + "is not here — attach to the session's own stream for that.")
    @GetMapping(value = "/users/{userId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter userStream(@PathVariable String userId, @CurrentUser UserId caller) {
        UserId user = UserPaths.requireSelf(userId, caller);
        log.info("GET /api/users/{}/stream", user);
        SseEmitter emitter = new SseEmitter(120_000L);

        var attachedSent = new java.util.concurrent.CountDownLatch(1);
        var subscription = management.lifecycle(user, event -> {
            try {
                attachedSent.await();
                emitter.send(SseEmitter.event().data(
                        compactMapper.writeValueAsString(UserEventFrame.of(event)), MediaType.APPLICATION_JSON));
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        try {
            emitter.send(SseEmitter.event().data(
                    compactMapper.writeValueAsString(UserEventFrame.Attached.now()), MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            subscription.close();
            emitter.completeWithError(e);
            return emitter;
        } finally {
            attachedSent.countDown();
        }
        emitter.onCompletion(subscription::close);
        emitter.onTimeout(subscription::close);
        emitter.onError(error -> subscription.close());
        return emitter;
    }

    @Operation(tags = "Sessions", summary = "Cancel the running chat turn",
            description = "Cooperative cancel: 204 if a live turn was signalled, 404 if none is "
                    + "running. The SSE stream still ends with its normal Done event once the "
                    + "loop reaches the next cancel-check point.")
    @DeleteMapping("/sessions/{sessionId}/chat")
    public ResponseEntity<Void> cancelChat(@PathVariable String sessionId, @CurrentUser UserId caller) {
        boolean cancelled = sessionTurns.cancel(owned(sessionId, caller));
        log.info("DELETE /api/sessions/{}/chat → cancelled={}", sessionId, cancelled);
        return cancelled ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @Operation(tags = "Sessions", summary = "Attach to the session's event stream",
            description = "The reconnect story: replays every buffered event after afterSeq, "
                    + "then continues live — a running turn's partial answer included. The "
                    + "first frame is {type:'attached'} with the buffer bounds and the live "
                    + "turn (null when idle); a firstBufferedSeq beyond afterSeq+1 means the "
                    + "replay has a gap and the client should refresh from the history. Every "
                    + "following frame carries seq (the cursor for the next reconnect), "
                    + "turnId and run around the usual event payload. The stream stays open "
                    + "across turns until the client disconnects or the emitter times out — "
                    + "reattaching with the last seen seq is the intended loop.")
    @GetMapping(value = "/sessions/{sessionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sessionStream(@PathVariable String sessionId,
                                    @RequestParam(defaultValue = "0") long afterSeq,
                                    @CurrentUser UserId caller) {
        SessionId id = owned(sessionId, caller);
        log.info("GET /api/sessions/{}/stream afterSeq={}", sessionId, afterSeq);
        SseEmitter emitter = new SseEmitter(120_000L);

        // The replay runs on the channel's drain thread and could outrun the
        // attached frame below — the latch holds event frames until it is out.
        var attachedSent = new java.util.concurrent.CountDownLatch(1);
        var attachment = frameStream.attach(id, afterSeq, frame -> {
            try {
                attachedSent.await();
                emitter.send(SseEmitter.event().data(compactMapper.writeValueAsString(frame),
                        MediaType.APPLICATION_JSON));
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        try {
            emitter.send(SseEmitter.event().data(
                    compactMapper.writeValueAsString(new AttachedFrame("attached",
                            attachment.firstBufferedSeq(), attachment.latestSeq(),
                            attachment.liveTurnId(), attachment.liveTurnId() == null ? null : 1)),
                    MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            attachment.subscription().close();
            emitter.completeWithError(e);
            return emitter;
        } finally {
            attachedSent.countDown();
        }
        emitter.onCompletion(attachment.subscription()::close);
        emitter.onTimeout(attachment.subscription()::close);
        emitter.onError(error -> attachment.subscription().close());
        return emitter;
    }

    // ── History & memory ────────────────────────────────────────────────────

    @Operation(tags = "Sessions", summary = "Load the session's message history")
    @GetMapping("/sessions/{sessionId}/history")
    public List<SessionMessageResponse> loadHistory(@PathVariable String sessionId, @CurrentUser UserId caller) {
        SessionId id = owned(sessionId, caller);
        log.info("GET /api/sessions/{}/history", sessionId);
        List<SessionMessageResponse> history = management.messages(id).stream()
                .map(SessionMessageResponse::of).toList();
        log.info("Returning {} message(s) for session {}", history.size(), sessionId);
        return history;
    }

    @Operation(tags = "Sessions", summary = "Delete a session")
    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> deleteSession(@PathVariable String sessionId, @CurrentUser UserId caller) {
        SessionId id = owned(sessionId, caller);
        log.info("DELETE /api/sessions/{}", sessionId);
        management.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(tags = "Sessions", summary = "Delete a range of messages",
            description = "Removes messages with sequence numbers in [fromSeq, toSeq] from the "
                    + "session history and returns how many were deleted.")
    @DeleteMapping("/sessions/{sessionId}/messages")
    public ResponseEntity<Map<String, Object>> deleteMessages(@PathVariable String sessionId,
                                                               @RequestParam int fromSeq,
                                                               @RequestParam int toSeq,
                                                               @CurrentUser UserId caller) {
        SessionId id = owned(sessionId, caller);
        log.info("DELETE /api/sessions/{}/messages fromSeq={} toSeq={}", sessionId, fromSeq, toSeq);
        if (fromSeq > toSeq) throw new IllegalArgumentException("fromSeq must be <= toSeq");
        int deleted = management.deleteMessages(id, fromSeq, toSeq);
        return ResponseEntity.ok(Map.of(
                "sessionId", sessionId,
                "deletedMessages", deleted));
    }

    @Operation(tags = "Sessions", summary = "Inspect the session's working memory",
            description = "The prompt-assembly view: which messages are live, compressed or "
                    + "truncated, plus token accounting.")
    @GetMapping("/sessions/{sessionId}/memory")
    public WorkingMemoryResponse getWorkingMemory(@PathVariable String sessionId, @CurrentUser UserId caller) {
        SessionId id = owned(sessionId, caller);
        log.info("GET /api/sessions/{}/memory", sessionId);
        return WorkingMemoryResponse.of(runtime.service(MemoryInspection.class).inspect(
                id));
    }

    @Operation(tags = "Sessions", summary = "Compress the session's working memory",
            description = "Summarises older turns to reclaim context window; returns how many "
                    + "messages were compressed.")
    @PostMapping("/sessions/{sessionId}/compress")
    public ResponseEntity<Map<String, Object>> compressMemory(@PathVariable String sessionId,
                                                              @CurrentUser UserId caller) {
        SessionId id = owned(sessionId, caller);
        log.info("POST /api/sessions/{}/compress", sessionId);
        int compressed = compaction.compact(id);
        return ResponseEntity.ok(Map.of(
                "sessionId", sessionId,
                "compressedMessages", compressed));
    }

    // ── Approvals ───────────────────────────────────────────────────────────

    @Operation(tags = "Sessions", summary = "List the open approval requests",
            description = "The still-unanswered questions of this conversation, oldest first — "
                    + "root tool calls and the ones bubbled up from sub-agents alike. The "
                    + "stream announces a request only in the moment it is raised, so a client "
                    + "that connects later (or reattaches after a restart) rebuilds its cards "
                    + "from here. Each entry's content is the call JSON the card shows.")
    @GetMapping("/sessions/{sessionId}/approvals")
    public List<ToolApproval> openApprovals(@PathVariable String sessionId, @CurrentUser UserId caller) {
        List<ToolApproval> open = openApprovals(owned(sessionId, caller));
        log.info("GET /api/sessions/{}/approvals → {} open", sessionId, open.size());
        return open;
    }

    @Operation(tags = "Sessions", summary = "Answer an approval request",
            description = "Deny, allow once, or allow the tool for the rest of the session "
                    + "(scope=once|session). The callId is the whole identity. No new stream: "
                    + "the turn never ended — it is suspended on the parked tool task and "
                    + "continues on its original stream the moment the decision arrives. "
                    + "204 when it was delivered, 404 for a stale card whose task is gone.")
    @PostMapping("/sessions/{sessionId}/approvals/{callId}")
    public ResponseEntity<Void> answerApproval(@PathVariable String sessionId,
                                               @PathVariable String callId,
                                               @RequestParam boolean approved,
                                               @RequestParam(defaultValue = "once") String scope,
                                               @CurrentUser UserId caller) {
        boolean delivered = sessionTurns.answerApproval(owned(sessionId, caller), callId, approved,
                "session".equalsIgnoreCase(scope)).isPresent();
        log.info("POST /api/sessions/{}/approvals/{} approved={} scope={} → delivered={}",
                sessionId, callId, approved, scope, delivered);
        return delivered ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @Operation(tags = "Sessions", summary = "Answer an approval request and stream the turn on",
            description = "Like POST …/approvals/{callId}, but the response is the continuation: "
                    + "the same turn as Server-Sent Events from where the caller's stream reported "
                    + "the question, including whatever happened meanwhile. It ends with Done when "
                    + "the turn completes, or with an incomplete frame listing the questions it "
                    + "still waits on — answer the next one the same way. 404 for a stale card.")
    @PostMapping(value = "/sessions/{sessionId}/approvals/{callId}/continue",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> answerApprovalAndContinue(@PathVariable String sessionId,
                                                                @PathVariable String callId,
                                                                @RequestParam boolean approved,
                                                                @RequestParam(defaultValue = "once") String scope,
                                                                @CurrentUser UserId caller) {
        SessionId id = owned(sessionId, caller);
        SseEmitter emitter = new SseEmitter(120_000L);
        boolean continued = frameStream.stream(id, () -> sessionTurns.answerApproval(id,
                callId, approved, "session".equalsIgnoreCase(scope)), true, sinkTo(emitter, id));
        log.info("POST /api/sessions/{}/approvals/{}/continue approved={} scope={} → continued={}",
                sessionId, callId, approved, scope, continued);
        return continued ? ResponseEntity.ok(emitter) : ResponseEntity.notFound().build();
    }

    /**
     * The session's id, once it is known to be the caller's — a 404 for the
     * request otherwise, whether the session is someone else's or missing.
     */

    /** What the session's turn waits for, as the approval cards a client draws. */
    private List<ToolApproval> openApprovals(SessionId sessionId) {
        return sessionTurns.open(sessionId).stream()
                .filter(open -> Interruption.TOOL_APPROVAL.equals(open.kind()) && open.call() != null)
                .map(open -> new ToolApproval(open.id(), open.call().callId(), open.call().toolName(),
                        callJson(open), open.call().sessionId(), sessionId, null, null))
                .toList();
    }

    private String callJson(Interruption open) {
        try {
            return compactMapper.writeValueAsString(open.call().arguments());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "{}";
        }
    }

    private SessionId owned(String sessionId, UserId caller) {
        SessionId id = SessionId.of(sessionId);
        sessionAccess.requireOwned(id, caller);
        return id;
    }
}
