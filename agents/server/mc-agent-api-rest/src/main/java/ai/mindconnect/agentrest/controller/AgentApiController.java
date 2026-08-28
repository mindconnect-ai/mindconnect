package ai.mindconnect.agentrest.controller;

import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentPatch;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.domain.AgentSpec;
import ai.mindconnect.agent.domain.StreamEvent;
import ai.mindconnect.agent.memory.domain.WorkingMemory;
import ai.mindconnect.agent.port.in.ChatTurnHandle;
import ai.mindconnect.agent.service.AgentChatService;
import ai.mindconnect.agent.service.AgentRegistryService;
import ai.mindconnect.agent.service.AgentSessionService;
import ai.mindconnect.agentrest.dto.CreateAgentRequest;
import ai.mindconnect.agentrest.dto.StartSessionRequest;
import ai.mindconnect.agentrest.dto.AttachedFrame;
import ai.mindconnect.agentrest.dto.SessionStreamFrame;
import ai.mindconnect.agentrest.dto.StreamEventFrame;
import ai.mindconnect.agentrest.dto.UpdateToolsRequest;
import ai.mindconnect.common.Namespace;
import ai.mindconnect.message.domain.Message;
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
import java.util.UUID;

/**
 * External REST API for agents and their chat sessions: agent CRUD, session
 * lifecycle, chat streaming, history and working memory. Delegates to the
 * same use-case services the admin UI runs on ({@link AgentRegistryService},
 * {@link AgentSessionService}, {@link AgentChatService}).
 */
@RestController
@RequestMapping("/api")
public class AgentApiController {

    private static final Logger log = LoggerFactory.getLogger(AgentApiController.class);

    private final AgentRegistryService registryService;
    private final AgentSessionService sessionService;
    private final AgentChatService chatService;
    private final ObjectMapper compactMapper;

    public AgentApiController(AgentRegistryService registryService,
                            AgentSessionService sessionService,
                            AgentChatService chatService,
                            ObjectMapper objectMapper) {
        this.registryService = registryService;
        this.sessionService = sessionService;
        this.chatService = chatService;
        this.compactMapper = objectMapper.copy().disable(
                com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);
    }

    // ── Agent CRUD ──────────────────────────────────────────────────────────

    @Operation(tags = "Agents", summary = "Create an agent",
            description = "Creates an agent in the namespace with the default workspace tools "
                    + "(workspace_read/write/list) pre-registered.")
    @PostMapping("/agents")
    public AgentDefinition createAgent(@RequestBody CreateAgentRequest req) {
        log.info("POST /api/agents name={} namespace={}", req.name(), req.namespace());
        AgentSpec spec = new AgentSpec(req.name(), req.description(),
                req.systemPrompt(), req.welcomeMessage(), req.llmConfigName());
        AgentDefinition agent = registryService.create(new Namespace(req.namespace()), spec);
        log.info("Created agent: {} ({})", agent.name(), agent.id());
        return agent;
    }

    /** Partial update: absent (null) fields keep their current value. */
    public record UpdateAgentRequest(String name, String description, String systemPrompt,
                                     String welcomeMessage, String llmConfigName,
                                     Integer maxIterations, List<String> responseReviewers,
                                     AgentDefinition.ToolSearchConfig toolSearch) {}

    @Operation(tags = "Agents", summary = "Update an agent",
            description = "Partial update — absent (null) fields keep their current value. "
                    + "Covers the same fields as the admin UI's edit form, through the same "
                    + "AgentRegistryService path.")
    @PutMapping("/agents/{agentId}")
    public AgentDefinition updateAgent(@PathVariable UUID agentId,
                                       @RequestParam String namespace,
                                       @RequestBody UpdateAgentRequest req) {
        log.info("PUT /api/agents/{} namespace={}", agentId, namespace);
        AgentPatch patch = AgentPatch.of()
                .withName(req.name())
                .withDescription(req.description())
                .withSystemPrompt(req.systemPrompt())
                .withWelcomeMessage(req.welcomeMessage())
                .withLlmConfigName(req.llmConfigName())
                .withMaxIterations(req.maxIterations())
                .withResponseReviewers(req.responseReviewers())
                .withToolSearch(req.toolSearch());
        return registryService.update(new Namespace(namespace), agentId, patch);
    }

    @Operation(tags = "Agents", summary = "Delete an agent")
    @DeleteMapping("/agents/{agentId}")
    public ResponseEntity<Void> deleteAgent(@PathVariable UUID agentId,
                                            @RequestParam String namespace) {
        log.info("DELETE /api/agents/{} namespace={}", agentId, namespace);
        Namespace ns = new Namespace(namespace);
        if (registryService.find(ns, agentId).isEmpty()) return ResponseEntity.notFound().build();
        registryService.delete(ns, agentId);
        return ResponseEntity.noContent().build();
    }

    @Operation(tags = "Agents", summary = "Duplicate an agent",
            description = "Creates \"{name}-copy\" with a fresh id and no tools "
                    + "(tools carry per-agent ids).")
    @PostMapping("/agents/{agentId}/copy")
    public ResponseEntity<AgentDefinition> copyAgent(@PathVariable UUID agentId,
                                                     @RequestParam String namespace) {
        log.info("POST /api/agents/{}/copy namespace={}", agentId, namespace);
        Namespace ns = new Namespace(namespace);
        if (registryService.find(ns, agentId).isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(registryService.copy(ns, agentId));
    }

    @Operation(tags = "Agents", summary = "Replace an agent's tools",
            description = "Sets the complete tool list — tools not in the request are removed.")
    @PutMapping("/agents/{agentId}/tools")
    public AgentDefinition updateTools(@PathVariable UUID agentId,
                                        @RequestParam String namespace,
                                        @RequestBody UpdateToolsRequest req) {
        log.info("PUT /api/agents/{}/tools namespace={} count={}", agentId, namespace, req.tools().size());
        AgentPatch patch = AgentPatch.of().withTools(req.tools());
        return registryService.update(new Namespace(namespace), agentId, patch);
    }

    @Operation(tags = "Agents", summary = "List agents in a namespace")
    @GetMapping("/agents")
    public List<AgentDefinition> listAgents(@RequestParam String namespace) {
        log.info("GET /api/agents namespace={}", namespace);
        return registryService.list(new Namespace(namespace));
    }

    @Operation(tags = "Agents", summary = "Get an agent")
    @GetMapping("/agents/{agentId}")
    public ResponseEntity<AgentDefinition> findAgent(@PathVariable UUID agentId,
                                                     @RequestParam String namespace) {
        log.info("GET /api/agents/{} namespace={}", agentId, namespace);
        return registryService.find(new Namespace(namespace), agentId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Sessions ────────────────────────────────────────────────────────────

    @Operation(tags = "Sessions", summary = "Start a chat session",
            description = "Opens a new session for the agent; the returned id addresses chat, "
                    + "history, memory and file endpoints.")
    @PostMapping("/sessions")
    public AgentSession startSession(@RequestBody StartSessionRequest req) {
        log.info("POST /api/sessions agentId={} namespace={} userId={}", req.agentId(), req.namespace(), req.userId());
        AgentSession session = sessionService.openChat(req.agentId(), new Namespace(req.namespace()), req.userId());
        log.info("Session started: {}", session.id());
        return session;
    }

    @Operation(tags = "Sessions", summary = "List a user's sessions for an agent")
    @GetMapping("/sessions")
    public List<AgentSession> listSessions(
            @RequestParam UUID agentId,
            @RequestParam String namespace,
            @RequestParam String userId) {
        log.info("GET /api/sessions agentId={} namespace={} userId={}", agentId, namespace, userId);
        List<AgentSession> sessions = sessionService.listSessions(agentId, new Namespace(namespace), userId);
        log.info("Found {} session(s)", sessions.size());
        return sessions;
    }

    // ── Chat ────────────────────────────────────────────────────────────────

    @Operation(tags = "Sessions", summary = "Send a chat message (SSE stream)",
            description = "Submits the message (plain-text body) and streams the turn as "
                    + "Server-Sent Events: token deltas, tool calls, task updates, then a final "
                    + "Done frame. The stream closes when the turn completes or fails.")
    @PostMapping(value = "/sessions/{sessionId}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streaming(@PathVariable UUID sessionId, @RequestBody String message) {
        log.info("POST /api/sessions/{}/chat message=\"{}\"", sessionId,
                message.length() > 80 ? message.substring(0, 80) + "…" : message);

        SseEmitter emitter = new SseEmitter(120_000L);

        ChatTurnHandle turn = chatService.submitChat(sessionId, message, event -> {
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

    @Operation(tags = "Sessions", summary = "Cancel the running chat turn",
            description = "Cooperative cancel: 204 if a live turn was signalled, 404 if none is "
                    + "running. The SSE stream still ends with its normal Done event once the "
                    + "loop reaches the next cancel-check point.")
    @DeleteMapping("/sessions/{sessionId}/chat")
    public ResponseEntity<Void> cancelChat(@PathVariable UUID sessionId) {
        boolean cancelled = chatService.cancelChat(sessionId);
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
    public SseEmitter sessionStream(@PathVariable UUID sessionId,
                                    @RequestParam(defaultValue = "0") long afterSeq) {
        log.info("GET /api/sessions/{}/stream afterSeq={}", sessionId, afterSeq);
        SseEmitter emitter = new SseEmitter(120_000L);

        // The replay runs on the channel's drain thread and could outrun the
        // attached frame below — the latch holds event frames until it is out.
        var attachedSent = new java.util.concurrent.CountDownLatch(1);
        AgentChatService.Attachment attachment = chatService.attach(sessionId, afterSeq, event -> {
            try {
                attachedSent.await();
                emitter.send(SseEmitter.event().data(
                        compactMapper.writeValueAsString(new SessionStreamFrame(
                                event.seq(),
                                event.value().turnId().toString(),
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
    public List<Message> loadHistory(@PathVariable UUID sessionId) {
        log.info("GET /api/sessions/{}/history", sessionId);
        List<Message> history = sessionService.loadHistory(sessionId);
        log.info("Returning {} message(s) for session {}", history.size(), sessionId);
        return history;
    }

    @Operation(tags = "Sessions", summary = "Delete a session")
    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> deleteSession(@PathVariable UUID sessionId) {
        log.info("DELETE /api/sessions/{}", sessionId);
        sessionService.deleteSession(sessionId);
        return ResponseEntity.noContent().build();
    }

    @Operation(tags = "Sessions", summary = "Delete a range of messages",
            description = "Removes messages with sequence numbers in [fromSeq, toSeq] from the "
                    + "session history and returns how many were deleted.")
    @DeleteMapping("/sessions/{sessionId}/messages")
    public ResponseEntity<Map<String, Object>> deleteMessages(@PathVariable UUID sessionId,
                                                               @RequestParam int fromSeq,
                                                               @RequestParam int toSeq) {
        log.info("DELETE /api/sessions/{}/messages fromSeq={} toSeq={}", sessionId, fromSeq, toSeq);
        int deleted = sessionService.deleteMessages(sessionId, fromSeq, toSeq);
        return ResponseEntity.ok(Map.of(
                "sessionId", sessionId,
                "deletedMessages", deleted));
    }

    @Operation(tags = "Sessions", summary = "Inspect the session's working memory",
            description = "The prompt-assembly view: which messages are live, compressed or "
                    + "truncated, plus token accounting.")
    @GetMapping("/sessions/{sessionId}/memory")
    public WorkingMemory getWorkingMemory(@PathVariable UUID sessionId) {
        log.info("GET /api/sessions/{}/memory", sessionId);
        return chatService.memorySnapshot(sessionId);
    }

    @Operation(tags = "Sessions", summary = "Compress the session's working memory",
            description = "Summarises older turns to reclaim context window; returns how many "
                    + "messages were compressed.")
    @PostMapping("/sessions/{sessionId}/compress")
    public ResponseEntity<Map<String, Object>> compressMemory(@PathVariable UUID sessionId) {
        log.info("POST /api/sessions/{}/compress", sessionId);
        int compressed = chatService.compressMemory(sessionId);
        return ResponseEntity.ok(Map.of(
                "sessionId", sessionId,
                "compressedMessages", compressed));
    }
}
