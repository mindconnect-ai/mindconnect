package ai.mindconnect.agentrest.controller;

import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentPatch;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.domain.AgentSpec;
import ai.mindconnect.agent.runtime.memory.domain.WorkingMemory;
import ai.mindconnect.agent.runtime.port.in.ChatTurnHandle;
import ai.mindconnect.agent.runtime.service.AgentChatService;
import ai.mindconnect.agent.runtime.service.AgentRegistryService;
import ai.mindconnect.agent.runtime.service.AgentSessionService;
import ai.mindconnect.agent.runtime.service.approval.ApprovalScope;
import ai.mindconnect.agent.runtime.service.approval.ToolApproval;
import ai.mindconnect.agentrest.auth.CurrentUser;
import ai.mindconnect.agentrest.auth.SessionAccess;
import ai.mindconnect.agentrest.dto.CreateAgentRequest;
import ai.mindconnect.agentrest.dto.StartSessionRequest;
import ai.mindconnect.agentrest.dto.AttachedFrame;
import ai.mindconnect.agentrest.dto.SessionStreamFrame;
import ai.mindconnect.agentrest.dto.UserEventFrame;
import ai.mindconnect.agentrest.dto.StreamEventFrame;
import ai.mindconnect.agentrest.dto.UpdateToolsRequest;
import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.filestore.FileId;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.agent.runtime.service.stream.UserChannels;
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

/**
 * External REST API for agents and their chat sessions: agent CRUD, session
 * lifecycle, chat streaming, history and working memory. Delegates to the
 * same use-case services the admin UI runs on ({@link AgentRegistryService},
 * {@link AgentSessionService}, {@link AgentChatService}).
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

    private final AgentRegistryService registryService;
    private final AgentSessionService sessionService;
    private final AgentChatService chatService;
    private final ai.mindconnect.filestore.FileStore fileStore;
    private final UserChannels userChannels;
    private final SessionAccess sessionAccess;
    private final ObjectMapper compactMapper;
    private final ai.mindconnect.agent.runtime.service.WorkingDirBrowser dirBrowser;

    public AgentApiController(AgentRegistryService registryService,
                            AgentSessionService sessionService,
                            AgentChatService chatService,
                            ai.mindconnect.filestore.FileStore fileStore,
                            UserChannels userChannels,
                            SessionAccess sessionAccess,
                            ObjectMapper objectMapper,
                            @org.springframework.lang.Nullable
                            ai.mindconnect.agent.runtime.service.WorkingDirBrowser dirBrowser) {
        // A host that defines no browser gets one over the session service's
        // policy — the same tree, without a bean to declare.
        this.dirBrowser = dirBrowser != null ? dirBrowser
                : new ai.mindconnect.agent.runtime.service.WorkingDirBrowser(sessionService.workingDirPolicy());
        this.registryService = registryService;
        this.sessionService = sessionService;
        this.chatService = chatService;
        this.fileStore = fileStore;
        this.userChannels = userChannels;
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
    public AgentDefinition createAgent(@RequestBody CreateAgentRequest req) {
        log.info("POST /api/agents name={}", req.name());
        AgentSpec spec = new AgentSpec(req.name(), req.description(),
                req.systemPrompt(), req.welcomeMessage(), req.llmConfigName());
        AgentDefinition agent = registryService.create(spec);
        log.info("Created agent: {} ({})", agent.name(), agent.id());
        return agent;
    }

    /**
     * Partial update: absent (null) fields keep their current value. {@code version},
     * when given, is the agent's version as read: the update is refused with 409 if the
     * agent was saved since.
     */
    public record UpdateAgentRequest(String name, String description, String systemPrompt,
                                     String welcomeMessage, String llmConfigName,
                                     Integer maxIterations, List<String> responseReviewers,
                                     AgentDefinition.ToolSearchConfig toolSearch, Long version) {

        /** Without a version: the update is applied to the agent as stored. */
        public UpdateAgentRequest(String name, String description, String systemPrompt,
                                  String welcomeMessage, String llmConfigName,
                                  Integer maxIterations, List<String> responseReviewers,
                                  AgentDefinition.ToolSearchConfig toolSearch) {
            this(name, description, systemPrompt, welcomeMessage, llmConfigName, maxIterations,
                    responseReviewers, toolSearch, null);
        }
    }

    @Operation(tags = "Agents", summary = "Update an agent",
            description = "Partial update — absent (null) fields keep their current value. "
                    + "Covers the same fields as the admin UI's edit form, through the same "
                    + "AgentRegistryService path. Send the agent's `version` as you read it to "
                    + "have the update refused with 409 when the agent was saved since; without "
                    + "it the update is applied to the agent as stored.")
    @PutMapping("/agents/{agentId}")
    public AgentDefinition updateAgent(@PathVariable String agentId,
                                       @RequestBody UpdateAgentRequest req) {
        log.info("PUT /api/agents/{}", agentId);
        AgentPatch patch = AgentPatch.of()
                .withName(req.name())
                .withDescription(req.description())
                .withSystemPrompt(req.systemPrompt())
                .withWelcomeMessage(req.welcomeMessage())
                .withLlmConfigName(req.llmConfigName())
                .withMaxIterations(req.maxIterations())
                .withResponseReviewers(req.responseReviewers())
                .withToolSearch(req.toolSearch());
        return registryService.update(AgentId.of(agentId), patch, req.version());
    }

    @Operation(tags = "Agents", summary = "Delete an agent")
    @DeleteMapping("/agents/{agentId}")
    public ResponseEntity<Void> deleteAgent(@PathVariable String agentId) {
        log.info("DELETE /api/agents/{}", agentId);
        AgentId id = AgentId.of(agentId);
        if (registryService.find(id).isEmpty()) return ResponseEntity.notFound().build();
        registryService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(tags = "Agents", summary = "Duplicate an agent",
            description = "Creates \"{name}-copy\" with a fresh id and no tools "
                    + "(tools carry per-agent ids).")
    @PostMapping("/agents/{agentId}/copy")
    public ResponseEntity<AgentDefinition> copyAgent(@PathVariable String agentId) {
        log.info("POST /api/agents/{}/copy", agentId);
        AgentId id = AgentId.of(agentId);
        if (registryService.find(id).isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(registryService.copy(id));
    }

    @Operation(tags = "Agents", summary = "Replace an agent's tools",
            description = "Sets the complete tool list — tools not in the request are removed.")
    @PutMapping("/agents/{agentId}/tools")
    public AgentDefinition updateTools(@PathVariable String agentId,
                                        @RequestBody UpdateToolsRequest req) {
        log.info("PUT /api/agents/{}/tools count={}", agentId, req.tools().size());
        // The tools come in the shape an agent is read in.
        AgentPatch patch = AgentPatch.of().withTools(req.tools().stream().map(t -> t.toTool()).toList());
        return registryService.update(AgentId.of(agentId), patch);
    }

    @Operation(tags = "Agents", summary = "List agents")
    @GetMapping("/agents")
    public List<AgentDefinition> listAgents() {
        log.info("GET /api/agents");
        return registryService.list();
    }

    @Operation(tags = "Agents", summary = "Get an agent")
    @GetMapping("/agents/{agentId}")
    public ResponseEntity<AgentDefinition> findAgent(@PathVariable String agentId) {
        log.info("GET /api/agents/{}", agentId);
        return registryService.find(AgentId.of(agentId))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Sessions ────────────────────────────────────────────────────────────

    @Operation(tags = "Sessions", summary = "Start a chat session",
            description = "Opens a new session for the agent, owned by the authenticated caller; "
                    + "the returned id addresses chat, history, memory and file endpoints. Body "
                    + "{agentId} — a userId still sent by an older client is ignored.")
    @PostMapping("/sessions")
    public AgentSession startSession(@RequestBody StartSessionRequest req, @CurrentUser UserId caller) {
        log.info("POST /api/sessions agentId={} user={} workingDir={}",
                req.agentId(), caller, req.workingDir());
        AgentSession session = sessionService.openChat(
                AgentId.of(req.agentId()), caller, req.workingDir(), req.additionalDirs());
        log.info("Session started: {}", session.id());
        return session;
    }

    @Operation(tags = "Sessions", summary = "List the directories the caller may work in",
            description = "One level of the server's tree under `mindconnect.tools.working-dir-root` "
                    + "(the caller's own root when it carries `{user}`): the directory at `path`, its "
                    + "parent, its sub-directories. Without `path`, the root. 400 when `path` lies "
                    + "outside the root or is no directory.")
    @GetMapping("/directories")
    public ai.mindconnect.agent.runtime.service.WorkingDirBrowser.Listing listDirectories(
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
    public AgentSession changeWorkingDir(@PathVariable String sessionId,
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
        return sessionService.changeWorkingDir(id, workingDir, additionalDirs);
    }

    @Operation(tags = "Sessions", summary = "List the caller's sessions for an agent")
    @GetMapping("/sessions")
    public List<AgentSession> listSessions(@RequestParam String agentId, @CurrentUser UserId caller) {
        log.info("GET /api/sessions agentId={} user={}", agentId, caller);
        List<AgentSession> sessions = sessionService.listSessions(AgentId.of(agentId), caller);
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

    /** Starts the turn and streams it as Server-Sent Events until it ends. */
    private SseEmitter streamTurn(SessionId sessionId, List<ai.mindconnect.message.domain.ContentPart> parts) {
        SseEmitter emitter = new SseEmitter(120_000L);

        ChatTurnHandle turn = chatService.submitChat(sessionId, parts, event -> {
            try {
                emitter.send(SseEmitter.event().data(
                        compactMapper.writeValueAsString(StreamEventFrame.from(event)),
                        MediaType.APPLICATION_JSON));
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });

        // Close the stream when the turn finishes (success or failure). The
        // service runs the turn on its own executor; we just observe.
        turn.result().whenComplete((response, error) -> {
            if (error != null) {
                Throwable cause = (error.getCause() != null) ? error.getCause() : error;
                log.error("Chat error for session {}: {}", sessionId, cause.getMessage());
                try {
                    emitter.send(SseEmitter.event().data(
                            compactMapper.writeValueAsString(
                                    new StreamEventFrame("error", cause.getMessage(),
                                            null, null, null, null, null, null, null, null, null,
                                            null, null, null, null, null, null)),
                            MediaType.APPLICATION_JSON));
                } catch (Exception ignored) {}
                emitter.completeWithError(cause);
            } else {
                log.info("Chat complete for session {}", sessionId);
                emitter.complete();
            }
        });

        return emitter;
    }

    @Operation(tags = "Sessions", summary = "Attach to the caller's event stream",
            description = "The path names the caller — their own user id or 'me'; any other user "
                    + "answers 404. The coarse feed across all of the caller's sessions: "
                    + "session_started, session_titled, turn_started, turn_finished, "
                    + "approval_requested and approval_answered — what a session list or a "
                    + "notification needs, without attaching to any session. Same reconnect story "
                    + "as the session stream: the first frame is {type:'attached'} with the buffer "
                    + "bounds, then every buffered event after afterSeq replays and the stream "
                    + "continues live; each frame carries seq, the cursor for the next "
                    + "reconnect. The tokens of a turn are not here — attach to the session's "
                    + "own stream for those.")
    @GetMapping(value = "/users/{userId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter userStream(@PathVariable String userId,
                                 @RequestParam(defaultValue = "0") long afterSeq,
                                 @CurrentUser UserId caller) {
        UserId user = UserPaths.requireSelf(userId, caller);
        log.info("GET /api/users/{}/stream afterSeq={}", user, afterSeq);
        SseEmitter emitter = new SseEmitter(120_000L);

        var attachedSent = new java.util.concurrent.CountDownLatch(1);
        ai.mindconnect.channel.Subscription subscription = userChannels.subscribe(user, afterSeq, event -> {
            try {
                attachedSent.await();
                emitter.send(SseEmitter.event().data(
                        compactMapper.writeValueAsString(
                                UserEventFrame.of(event.seq(), event.value())),
                        MediaType.APPLICATION_JSON));
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        try {
            emitter.send(SseEmitter.event().data(
                    compactMapper.writeValueAsString(UserEventFrame.Attached.of(
                            userChannels.earliestBufferedSeq(user), userChannels.lastSeq(user))),
                    MediaType.APPLICATION_JSON));
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
        boolean cancelled = chatService.cancelChat(owned(sessionId, caller));
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
        AgentChatService.Attachment attachment = chatService.attach(id, afterSeq, event -> {
            try {
                attachedSent.await();
                emitter.send(SseEmitter.event().data(
                        compactMapper.writeValueAsString(new SessionStreamFrame(
                                event.seq(),
                                event.value().turnId().value(),
                                event.value().run(),
                                StreamEventFrame.from(event.value().event()))),
                        MediaType.APPLICATION_JSON));
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        try {
            emitter.send(SseEmitter.event().data(
                    compactMapper.writeValueAsString(AttachedFrame.of(
                            attachment.firstBufferedSeq(), attachment.latestSeq(),
                            attachment.liveTurnId(), attachment.liveRun())),
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
    public List<Message> loadHistory(@PathVariable String sessionId, @CurrentUser UserId caller) {
        SessionId id = owned(sessionId, caller);
        log.info("GET /api/sessions/{}/history", sessionId);
        List<Message> history = sessionService.loadHistory(id);
        log.info("Returning {} message(s) for session {}", history.size(), sessionId);
        return history;
    }

    @Operation(tags = "Sessions", summary = "Delete a session")
    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> deleteSession(@PathVariable String sessionId, @CurrentUser UserId caller) {
        SessionId id = owned(sessionId, caller);
        log.info("DELETE /api/sessions/{}", sessionId);
        sessionService.deleteSession(id);
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
        int deleted = sessionService.deleteMessages(id, fromSeq, toSeq);
        return ResponseEntity.ok(Map.of(
                "sessionId", sessionId,
                "deletedMessages", deleted));
    }

    @Operation(tags = "Sessions", summary = "Inspect the session's working memory",
            description = "The prompt-assembly view: which messages are live, compressed or "
                    + "truncated, plus token accounting.")
    @GetMapping("/sessions/{sessionId}/memory")
    public WorkingMemory getWorkingMemory(@PathVariable String sessionId, @CurrentUser UserId caller) {
        SessionId id = owned(sessionId, caller);
        log.info("GET /api/sessions/{}/memory", sessionId);
        return chatService.memorySnapshot(id);
    }

    @Operation(tags = "Sessions", summary = "Compress the session's working memory",
            description = "Summarises older turns to reclaim context window; returns how many "
                    + "messages were compressed.")
    @PostMapping("/sessions/{sessionId}/compress")
    public ResponseEntity<Map<String, Object>> compressMemory(@PathVariable String sessionId,
                                                              @CurrentUser UserId caller) {
        SessionId id = owned(sessionId, caller);
        log.info("POST /api/sessions/{}/compress", sessionId);
        int compressed = chatService.compressMemory(id);
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
        List<ToolApproval> open = chatService.openApprovals(owned(sessionId, caller));
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
        boolean delivered = chatService.answerApproval(
                owned(sessionId, caller), callId, approved, ApprovalScope.fromParam(scope));
        log.info("POST /api/sessions/{}/approvals/{} approved={} scope={} → delivered={}",
                sessionId, callId, approved, scope, delivered);
        return delivered ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /**
     * The session's id, once it is known to be the caller's — a 404 for the
     * request otherwise, whether the session is someone else's or missing.
     */
    private SessionId owned(String sessionId, UserId caller) {
        return sessionAccess.requireOwned(SessionId.of(sessionId), caller).id();
    }
}
