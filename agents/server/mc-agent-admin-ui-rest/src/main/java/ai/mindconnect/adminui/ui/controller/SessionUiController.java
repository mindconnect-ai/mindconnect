package ai.mindconnect.adminui.ui.controller;


import ai.mindconnect.adminui.ui.component.TaskCardComponent;
import ai.mindconnect.adminui.ui.page.ChatPage;
import ai.mindconnect.adminui.ui.page.MemoryPage;
import ai.mindconnect.adminui.ui.page.TodosPage;
import ai.mindconnect.adminui.ui.page.TracesPage;
import ai.mindconnect.adminui.ui.page.WorkspacePage;
import ai.mindconnect.agent.domain.StreamEvent;
import ai.mindconnect.agent.memory.domain.WorkingMemory;
import ai.mindconnect.agent.tools.workspace.WorkspaceScope;
import ai.mindconnect.agent.port.in.ChatTurnHandle;
import ai.mindconnect.agent.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.port.out.AgentSessionRepository;
import ai.mindconnect.agent.tools.workspace.WorkspaceStore;
import ai.mindconnect.agent.service.AgentChatService;
import ai.mindconnect.agent.service.AgentSessionService;
import ai.mindconnect.agent.tools.todo.TodoListService;
import ai.mindconnect.common.LoggingContext;
import ai.mindconnect.common.Namespace;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.ui.model.UiList;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiPatch;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

@RestController
@RequestMapping("/admin/api")
public class SessionUiController {

    private static final Logger log = LoggerFactory.getLogger(SessionUiController.class);

    private final AgentSessionService sessionService;
    private final AgentChatService chatService;
    private final AgentDefinitionRepository agentRepository;
    private final AgentSessionRepository sessionRepository;
    private final TodoListService todoListService;
    private final WorkspaceStore workspaceStore;
    private final ObjectMapper objectMapper;
    /** Optional — null in setups where trace persistence is disabled. */
    private final ai.mindconnect.agent.port.out.LlmCallTraceRepository traceRepository;
    private final ai.mindconnect.adminui.service.ActiveStreams activeStreams;

    private final ai.mindconnect.agentrest.service.SessionFileService sessionFiles;
    private final ai.mindconnect.adminui.ui.AdminLayoutFactory layoutFactory;
    private final ai.mindconnect.agent.service.approval.ToolApprovalStore approvalStore;

    public SessionUiController(AgentSessionService sessionService,
                             AgentChatService chatService,
                             AgentDefinitionRepository agentRepository,
                             AgentSessionRepository sessionRepository,
                             TodoListService todoListService,
                             WorkspaceStore workspaceStore,
                             Namespace defaultNamespace,
                             ObjectMapper objectMapper,
                             ai.mindconnect.agent.port.out.LlmCallTraceRepository traceRepository,
                             ai.mindconnect.adminui.service.ActiveStreams activeStreams,
                             ai.mindconnect.agentrest.service.SessionFileService sessionFiles,
                             ai.mindconnect.adminui.ui.AdminLayoutFactory layoutFactory,
                             ai.mindconnect.agent.service.approval.ToolApprovalStore approvalStore) {
        this.sessionService = sessionService;
        this.sessionFiles = sessionFiles;
        this.chatService = chatService;
        this.agentRepository = agentRepository;
        this.sessionRepository = sessionRepository;
        this.todoListService = todoListService;
        this.workspaceStore = workspaceStore;
        this.objectMapper = objectMapper;
        this.traceRepository = traceRepository;
        this.activeStreams = activeStreams;
        this.layoutFactory = layoutFactory;
        this.approvalStore = approvalStore;
    }

    @PostMapping("/agents/{agentId}/sessions")
    public ResponseEntity<UiPage> startSession(@PathVariable UUID agentId,
                                               @AuthenticationPrincipal OidcUser user) {
        String userId = user.getPreferredUsername();
        return agentRepository.findById(agentId)
                .map(agent -> {
                    var session = sessionService.openChat(agentId, agent.namespace(), userId);
                    return ResponseEntity.ok(buildChatPage(session, agent).render());
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Wraps a session tool page (memory / traces / todos / workspace) in a
     * wide dialog over whatever is on screen — a remove+append patch on the
     * body-level dialog host (same pattern as the tool-test dialogs). The
     * chat page underneath is never re-rendered, so scroll position and
     * stream state survive; × and backdrop close client-side.
     */
    private ResponseEntity<UiPatch> sessionDialog(UUID sessionId, String title, UiPage inner) {
        var dlg = ai.mindconnect.ui.model.UiDialog.of(title, null, inner.getNode());
        dlg.setId("session-dialog");
        dlg.withCssClass("sui-dialog--wide");
        return ResponseEntity.ok(UiPatch.of()
                .patch(UiPatch.Operation.remove("session-dialog"))
                .patch(UiPatch.Operation.append("sui-dialogs", dlg)));
    }

    /**
     * The attach dialog the chat form's "+" opens: the drop-zone in a modal,
     * patched over the untouched chat page. Uploads patch the page's
     * chat-attachments panel behind the dialog, so the chips are current
     * the moment it closes.
     */
    @GetMapping("/sessions/{sessionId}/attach-dialog")
    public ResponseEntity<UiPatch> attachDialog(@PathVariable UUID sessionId) {
        if (sessionRepository.findById(sessionId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        var dlg = ai.mindconnect.ui.model.UiDialog.of("Attach files", null,
                ai.mindconnect.adminui.ui.page.ChatPage.attachZone(sessionId));
        dlg.setId("session-dialog");
        return ResponseEntity.ok(UiPatch.of()
                .patch(UiPatch.Operation.remove("session-dialog"))
                .patch(UiPatch.Operation.append("sui-dialogs", dlg)));
    }

    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<UiPage> getSession(@PathVariable UUID sessionId) {
        return sessionRepository.findById(sessionId)
                .flatMap(session -> agentRepository.findById(session.agentDefinitionId())
                        .map(agent -> ResponseEntity.ok(buildChatPage(session, agent).render())))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Loads the current history + memory snapshot for the session and
     * wraps them in a {@link ChatPage} bound to the given agent. All
     * chat-page rendering and patch generation goes through this single
     * factory so the controller never reaches for the model directly.
     */
    private ChatPage buildChatPage(ai.mindconnect.agent.domain.AgentSession session,
                                    ai.mindconnect.agent.domain.AgentDefinition agent) {
        var history = sessionService.loadHistory(session.id());
        var memory  = safeMemorySnapshot(session.id());
        // Source-of-truth for "is this turn currently streaming?" is the
        // registry — not page-local state. Lets a navigate-back during a
        // live turn render the form in Stop-mode without any client-side
        // reconciliation.
        String channelId = "msg-list-" + session.id();
        var handleOpt = activeStreams.findHandle(channelId);
        var page = new ChatPage(session, agent, history, memory, handleOpt.isPresent(),
                (toolCallId, running, in, out) ->
                        buildSubAgentCards(session.id(), toolCallId, running, in, out))
                .withAttachments(ai.mindconnect.adminui.ui.component.ChatAttachmentsComponent.node(session.id(), sessionFiles.listAttachments(session.id())))
                .withBubbledApprovals(bubbledApprovalCards(session.id()));
        // Hand the SPA the resume URL for this session's stream (when
        // any). The bus re-attaches via GET on every applyPage so F5,
        // tab close/reopen, and second-tab observers all converge.
        handleOpt.ifPresent(h -> page.withActiveStreams(java.util.List.of(
                ai.mindconnect.ui.model.UiPage.ActiveStream.of(
                        h.channelId(),
                        "/admin/api/streams/" + h.channelId() + "/sse",
                        h.label(),
                        h.returnHref()))));
        return page;
    }

    /**
     * The cards for this session's OPEN sub-agent approval questions — read
     * from the ToolApprovalStore, the single truth for bubbled requests
     * (entry exists = card shows; answered/cancelled/deleted = entry gone).
     */
    private List<ai.mindconnect.ui.model.UiList.Item> bubbledApprovalCards(UUID sessionId) {
        return approvalStore.openForRoot(sessionId).stream()
                .map(open -> {
                    var call = ai.mindconnect.adminui.ui.component.MessageListComponent
                            .parseApprovalContent(open.content());
                    return ai.mindconnect.adminui.ui.component.MessageListComponent.approvalCard(
                            sessionId, open.callId(), call.toolName(), call.argsJson(),
                            ai.mindconnect.adminui.assembler.session.SessionUiCommons.DT_FMT
                                    .format(open.requestedAt()));
                })
                .toList();
    }

    /**
     * Working-memory debug page. Without {@code seq}: full master-detail page
     * with the system prompt pre-selected. With {@code seq}: a UiPatch that
     * replaces only the detail pane (clicked from the master list).
     */
    @GetMapping("/sessions/{sessionId}/memory")
    public ResponseEntity<?> getMemory(@PathVariable UUID sessionId,
                                        @RequestParam(value = "seq", required = false) Integer seq,
                                        @RequestParam(value = "dialog", defaultValue = "false") boolean dialog) {
        var memory = safeMemorySnapshot(sessionId);
        if (memory == null) {
            return ResponseEntity.status(503).body("Working memory unavailable for this session");
        }
        return sessionRepository.findById(sessionId)
                .flatMap(session -> agentRepository.findById(session.agentDefinitionId())
                        .map(agent -> {
                            var page = new MemoryPage(session, agent, memory);
                            if (seq != null) return ResponseEntity.ok(page.selectEntry(seq));
                            if (dialog) return sessionDialog(sessionId, "Working Memory", page.render());
                            return ResponseEntity.ok(page.render());
                        }))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Compresses unsummarized messages via the configured memory strategy and
     * re-renders the working-memory page with the new (smaller) snapshot, so
     * the operator immediately sees the effect of the compression — fewer
     * tokens, freshly-marked {@code (compressed)} entries, etc. Returns a
     * full UiPage so the front-end's existing data-action handler swaps the
     * page in place.
     */
    @PostMapping("/sessions/{sessionId}/memory/compress")
    public ResponseEntity<?> compressMemory(@PathVariable UUID sessionId) {
        try {
            chatService.compressMemory(sessionId);
        } catch (Exception e) {
            log.warn("Failed to compress memory for session {}: {}", sessionId, e.getMessage());
            return ResponseEntity.status(500).body("Compression failed: " + e.getMessage());
        }
        var memory = safeMemorySnapshot(sessionId);
        if (memory == null) {
            return ResponseEntity.status(503).body("Working memory unavailable after compression");
        }
        return sessionRepository.findById(sessionId)
                .flatMap(session -> agentRepository.findById(session.agentDefinitionId())
                        .map(agent -> ResponseEntity.ok(new MemoryPage(session, agent, memory).render())))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Per-turn LLM call traces. Without {@code turnId}: full master-detail
     * page (most recent turn pre-selected). With {@code turnId}: a UiPatch
     * that swaps only the detail pane to show that turn's roundtrips.
     */
    @GetMapping("/sessions/{sessionId}/traces")
    public ResponseEntity<?> getTraces(@PathVariable UUID sessionId,
                                        @RequestParam(value = "turnId", required = false) UUID turnId,
                                        @RequestParam(value = "dialog", defaultValue = "false") boolean dialog) {
        if (traceRepository == null) {
            return ResponseEntity.status(503).body("LLM call trace persistence is not enabled");
        }

        // Walk the session tree directly via parentSessionId: top-level
        // session + every sub-agent session (transitively) it spawned. For
        // each session we know the conversationId, so we read traces and
        // history straight from those known paths — no scanning every
        // conversation directory on disk.
        List<UUID> sessionIds = collectSessionTree(sessionId);
        List<ai.mindconnect.agent.domain.LlmCallTrace> traces = new java.util.ArrayList<>();
        for (UUID sid : sessionIds) {
            try {
                UUID convId = sessionRepository.findById(sid)
                        .map(s -> s.conversationId()).orElse(null);
                if (convId == null) continue;
                traces.addAll(traceRepository.findByConversation(convId));
            } catch (Exception e) {
                log.warn("Failed to load traces for session {}: {}", sid, e.getMessage());
            }
        }
        traces.sort(java.util.Comparator.comparing(
                ai.mindconnect.agent.domain.LlmCallTrace::startedAt));

        // History needs to span every session in the tree so the trace UI
        // can show TOOL_RESULT messages alongside their tool calls.
        List<Message> combinedHistory = new java.util.ArrayList<>();
        for (UUID sid : sessionIds) {
            try {
                combinedHistory.addAll(sessionService.loadHistory(sid));
            } catch (Exception e) {
                log.warn("Failed to load history for session {}: {}", sid, e.getMessage());
            }
        }

        final List<ai.mindconnect.agent.domain.LlmCallTrace> tracesFinal = traces;
        final List<Message> historyFinal = combinedHistory;
        return sessionRepository.findById(sessionId)
                .flatMap(session -> agentRepository.findById(session.agentDefinitionId())
                        .map(agent -> {
                            var page = new TracesPage(session, agent,
                                    tracesFinal, historyFinal, turnId);
                            if (turnId != null) return ResponseEntity.ok(page.selectTurn(turnId));
                            if (dialog) return sessionDialog(sessionId, "Traces", page.render());
                            return ResponseEntity.ok(page.render());
                        }))
                .orElse(ResponseEntity.notFound().build());
    }

    // ── todos page ─────────────────────────────────────────────────────────

    /**
     * Todo-list inspector page. Shows the same checklist the LLM is being fed
     * via {@code todo_list_md} in its prompt context.
     */
    @GetMapping("/sessions/{sessionId}/todos")
    public ResponseEntity<?> getTodos(@PathVariable UUID sessionId,
                                      @RequestParam(value = "dialog", defaultValue = "false") boolean dialog) {
        return sessionRepository.findById(sessionId)
                .flatMap(session -> agentRepository.findById(session.agentDefinitionId())
                        .map(agent -> {
                            var list = todoListService.load(sessionId);
                            var page = new TodosPage(session, agent, list).render();
                            if (dialog) return sessionDialog(sessionId, "Todos", page);
                            return ResponseEntity.<UiPage>ok(page);
                        }))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Resets the session's todo list. Returns the re-rendered todos page so
     * the operator sees the empty state immediately.
     */
    @DeleteMapping("/sessions/{sessionId}/todos")
    public ResponseEntity<?> clearTodos(@PathVariable UUID sessionId) {
        todoListService.clear(sessionId);
        return getTodos(sessionId, false);
    }

    // ── workspace page ─────────────────────────────────────────────────────

    /**
     * Workspace browser page — three scopes (SESSION / AGENT_USER / USER)
     * each with a file listing, sizes, and view/download links per file.
     */
    @GetMapping("/sessions/{sessionId}/workspace")
    public ResponseEntity<?> getWorkspace(@PathVariable UUID sessionId,
                                          @RequestParam(value = "dialog", defaultValue = "false") boolean dialog) {
        return sessionRepository.findById(sessionId)
                .flatMap(session -> agentRepository.findById(session.agentDefinitionId())
                        .map(agent -> {
                            var page = new WorkspacePage(session, agent, workspaceStore).render();
                            if (dialog) return sessionDialog(sessionId, "Workspace", page);
                            return ResponseEntity.<UiPage>ok(page);
                        }))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Streams a single workspace file back to the browser. {@code scope}
     * picks the workspace ({@code session} / {@code agent} / {@code user}),
     * {@code name} is the filename. With {@code download=true} the response
     * carries a {@code Content-Disposition: attachment} so the browser
     * downloads instead of rendering inline.
     */
    @GetMapping("/sessions/{sessionId}/workspace/{scope}/file")
    public ResponseEntity<byte[]> downloadWorkspaceFile(
            @PathVariable UUID sessionId,
            @PathVariable String scope,
            @RequestParam("name") String name,
            @RequestParam(value = "download", defaultValue = "false") boolean download) {
        var sessionOpt = sessionRepository.findById(sessionId);
        if (sessionOpt.isEmpty()) return ResponseEntity.notFound().build();
        var session = sessionOpt.get();

        WorkspaceScope ws = resolveScope(scope, session);
        if (ws == null) return ResponseEntity.badRequest().build();

        // Filename sanity check — keep it to a single segment, no traversal.
        if (name == null || name.contains("/") || name.contains("\\") || name.contains("..")) {
            return ResponseEntity.badRequest().build();
        }

        var bytesOpt = workspaceStore.readBytes(ws, name);
        if (bytesOpt.isEmpty()) return ResponseEntity.notFound().build();
        byte[] bytes = bytesOpt.get();

        MediaType type = guessContentType(name);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(type);
        headers.setContentLength(bytes.length);
        if (download) {
            headers.setContentDisposition(org.springframework.http.ContentDisposition
                    .attachment().filename(name).build());
        } else {
            headers.setContentDisposition(org.springframework.http.ContentDisposition
                    .inline().filename(name).build());
        }
        return ResponseEntity.ok().headers(headers).body(bytes);
    }

    private WorkspaceScope resolveScope(String key,
                                         ai.mindconnect.agent.domain.AgentSession session) {
        return switch (key) {
            case "session" -> WorkspaceScope.session(session.agentDefinitionId(),
                                                    session.userId(), session.id());
            case "agent"   -> WorkspaceScope.agentUser(session.agentDefinitionId(),
                                                       session.userId());
            case "user"    -> WorkspaceScope.user(session.userId());
            default        -> null;
        };
    }

    private static MediaType guessContentType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".md"))   return MediaType.valueOf("text/markdown;charset=UTF-8");
        if (lower.endsWith(".txt"))  return MediaType.valueOf("text/plain;charset=UTF-8");
        if (lower.endsWith(".json")) return MediaType.APPLICATION_JSON;
        if (lower.endsWith(".csv"))  return MediaType.valueOf("text/csv;charset=UTF-8");
        if (lower.endsWith(".html") || lower.endsWith(".htm"))
                                     return MediaType.TEXT_HTML;
        if (lower.endsWith(".xml"))  return MediaType.APPLICATION_XML;
        if (lower.endsWith(".yaml") || lower.endsWith(".yml"))
                                     return MediaType.valueOf("application/yaml");
        if (lower.endsWith(".pdf"))  return MediaType.APPLICATION_PDF;
        if (lower.endsWith(".png"))  return MediaType.IMAGE_PNG;
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg"))
                                     return MediaType.IMAGE_JPEG;
        if (lower.endsWith(".gif"))  return MediaType.IMAGE_GIF;
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    /**
     * Walks the parent/child session tree rooted at {@code rootSessionId}
     * via {@link AgentSessionRepository#findByParentSessionId(UUID)} and
     * returns every session id encountered (root first, then BFS through
     * sub-agents). Stays cheap because session.json files are tiny.
     */
    private List<UUID> collectSessionTree(UUID rootSessionId) {
        List<UUID> ordered = new java.util.ArrayList<>();
        java.util.ArrayDeque<UUID> frontier = new java.util.ArrayDeque<>();
        java.util.Set<UUID> visited = new java.util.HashSet<>();
        frontier.add(rootSessionId);
        while (!frontier.isEmpty()) {
            UUID current = frontier.poll();
            if (!visited.add(current)) continue;
            ordered.add(current);
            try {
                for (var sub : sessionRepository.findByParentSessionId(current)) {
                    frontier.add(sub.id());
                }
            } catch (Exception e) {
                log.warn("Failed to list sub-sessions of {}: {}", current, e.getMessage());
            }
        }
        return ordered;
    }

    /**
     * Rebuilds the nested sub-agent card tree for a single parent
     * {@code toolCallId} on a full page render.
     *
     * <p>Finds every sub-session that {@code parentSessionId} spawned via
     * that exact tool call (matched on {@code parentToolCallId} — works for
     * parallel {@code run_agents} batches, where several sessions share one
     * tool-call id), loads each one's history, and builds a
     * {@link TaskCardComponent} whose body is that sub-agent's own
     * (recursively-built) task tree plus its final answer. Recursion bottoms
     * out naturally when a sub-session spawned no further sub-agents.
     *
     * <p>{@code running} is supplied by the caller and reflects whether the
     * parent has a persisted TOOL_RESULT for this call yet — NOT the child
     * session's own status, which is unreliable (sessions are never moved
     * out of {@code ACTIVE} on disk). When done, "failed" is inferred from
     * the sub-agent having produced no final assistant text.
     */
    private List<TaskCardComponent> buildSubAgentCards(UUID parentSessionId, String toolCallId,
                                                       boolean running, String inputJson, String resultText) {
        if (toolCallId == null || toolCallId.isBlank()) return List.of();
        List<ai.mindconnect.agent.domain.AgentSession> children;
        try {
            children = sessionRepository.findByParentSessionId(parentSessionId).stream()
                    .filter(s -> toolCallId.equals(s.parentToolCallId()))
                    .sorted(java.util.Comparator.comparing(
                            ai.mindconnect.agent.domain.AgentSession::startedAt))
                    .toList();
        } catch (Exception e) {
            log.warn("Failed to list sub-sessions of {} for toolCall {}: {}",
                    parentSessionId, toolCallId, e.getMessage());
            return List.of();
        }

        List<TaskCardComponent> cards = new java.util.ArrayList<>();
        for (var child : children) {
            try {
                var childAgent = agentRepository.findById(child.agentDefinitionId()).orElse(null);
                String agentName = childAgent != null ? childAgent.name() : "sub-agent";
                List<Message> childHistory = sessionService.loadHistory(child.id());

                // A throwaway component reuses all the historic-card grouping
                // logic for the sub-session, recursing through the same
                // provider scoped to THIS child's id. A nested sub-agent's
                // running state likewise follows its own parent-result
                // presence, computed inside that recursive call.
                var childComp = new ai.mindconnect.adminui.ui.component.MessageListComponent(
                        child.id(), childAgent, childHistory, null,
                        (tcId, r, in, out) -> buildSubAgentCards(child.id(), tcId, r, in, out));

                var childList = TaskCardComponent.subAgentChildList(child.id().toString());
                for (TaskCardComponent t : childComp.allHistoricTaskCards()) {
                    childList.item(((UiList) t.render()).getItems().get(0));
                }

                // Per-child Input: the sub-agent's own first user message (the
                // task it was given) reads better than the shared parent args,
                // especially for run_agents batches. Fall back to the parent's
                // call args when the child has no user message yet.
                String childInput = firstUserText(childHistory);
                if (childInput == null || childInput.isBlank()) childInput = inputJson;

                // While still running, hold back the answer block — it isn't
                // final yet (the live done patch appends it on completion).
                String finalText = running ? null : childComp.lastAssistantText();
                boolean failed = !running && (finalText == null || finalText.isBlank());
                // Node id MUST match the live stream's id (task-sub-{sessionId})
                // so a reload mid-run produces the same <li> and the run's
                // continuing live patches keep landing on it.
                String nodeId = "task-sub-" + child.id();
                cards.add(TaskCardComponent.historicSubAgent(
                        nodeId, agentName, child.id().toString(),
                        running, failed, durationOf(child), childInput, resultText,
                        childList, finalText));
            } catch (Exception e) {
                log.warn("Failed to build sub-agent card for session {}: {}",
                        child.id(), e.getMessage());
            }
        }
        return cards;
    }

    /** Wall-clock duration of a (completed) session in ms, or 0 when unknown. */
    private static long durationOf(ai.mindconnect.agent.domain.AgentSession s) {
        if (s.startedAt() == null || s.completedAt() == null) return 0L;
        return java.time.Duration.between(s.startedAt(), s.completedAt()).toMillis();
    }

    /** The first USER CHAT message in a (sub-)session — the task it was given. */
    private static String firstUserText(List<Message> history) {
        return history.stream()
                .filter(m -> m.type() == ai.mindconnect.message.domain.MessageType.CHAT)
                .filter(m -> m.senderType() == ai.mindconnect.message.domain.ParticipantType.USER)
                .min(java.util.Comparator.comparingInt(Message::sequenceNum))
                .map(Message::content)
                .orElse(null);
    }

    @PostMapping("/sessions/{sessionId}/chat")
    public ResponseEntity<UiPatch> chat(@PathVariable UUID sessionId,
                                        @RequestBody Map<String, Object> raw) {
        var body    = new FormBody(raw);
        String text = body.str("message");
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        var sessionOpt = sessionRepository.findById(sessionId);
        if (sessionOpt.isEmpty()) return ResponseEntity.notFound().build();
        var agentOpt = agentRepository.findById(sessionOpt.get().agentDefinitionId());
        if (agentOpt.isEmpty()) return ResponseEntity.notFound().build();
        var agent = agentOpt.get();

        try (var ctx = LoggingContext.session(sessionId, null, agent.name())) {
            ChatTurnHandle turn = chatService.submitChat(sessionId, text, streamLogger(agent.name()));
            turn.result().join();
        }

        return ResponseEntity.ok(buildChatPage(sessionOpt.get(), agent).chatTurnComplete());
    }

    /**
     * Cooperatively cancels a running chat turn. Returns 204 if a live turn
     * was signalled, 404 if no chat is currently running. The stream
     * completes via the normal {@code Done} flow once the loop reaches its
     * next cancel-check point.
     */
    @DeleteMapping("/sessions/{sessionId}/chat")
    public ResponseEntity<Void> cancelChat(@PathVariable UUID sessionId) {
        boolean cancelled = chatService.cancelChat(sessionId);
        log.info("DELETE /admin/api/sessions/{}/chat → cancelled={}", sessionId, cancelled);
        return cancelled ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /**
     * Deletes a range of messages by sequenceNum ({@code fromSeq}..{@code toSeq}
     * inclusive). The UI's "delete from here" button passes
     * {@code toSeq=Integer.MAX_VALUE} to drop this message and everything after
     * it. Returns a UI patch that refreshes the conversation list (with the
     * updated header tokens) so the deleted items disappear. Sub-agent sessions
     * spawned by the removed turns are not cleaned up.
     */
    @DeleteMapping("/sessions/{sessionId}/messages")
    public ResponseEntity<UiPatch> deleteMessages(@PathVariable UUID sessionId,
                                                   @RequestParam int fromSeq,
                                                   @RequestParam int toSeq) {
        log.info("DELETE /admin/api/sessions/{}/messages fromSeq={} toSeq={}", sessionId, fromSeq, toSeq);
        var sessionOpt = sessionRepository.findById(sessionId);
        if (sessionOpt.isEmpty()) return ResponseEntity.notFound().build();
        var agentOpt = agentRepository.findById(sessionOpt.get().agentDefinitionId());
        if (agentOpt.isEmpty()) return ResponseEntity.notFound().build();

        sessionService.deleteMessages(sessionId, fromSeq, toSeq);

        return ResponseEntity.ok(
                buildChatPage(sessionOpt.get(), agentOpt.get()).headerOnly());
    }

    @PostMapping(value = "/sessions/{sessionId}/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> chatStream(@PathVariable UUID sessionId,
                                                 @RequestBody Map<String, Object> raw) {
        var body = new FormBody(raw);
        String text = body.str("message");
        if (text == null || text.isBlank()) {
            var emitter = new SseEmitter();
            emitter.completeWithError(new IllegalArgumentException("message required"));
            return ResponseEntity.ok(emitter);
        }

        var sessionOpt = sessionRepository.findById(sessionId);
        if (sessionOpt.isEmpty()) {
            var emitter = new SseEmitter();
            emitter.completeWithError(new IllegalStateException("session not found"));
            return ResponseEntity.ok(emitter);
        }
        var agentOpt = agentRepository.findById(sessionOpt.get().agentDefinitionId());
        if (agentOpt.isEmpty()) {
            var emitter = new SseEmitter();
            emitter.completeWithError(new IllegalStateException("agent not found"));
            return ResponseEntity.ok(emitter);
        }
        return runChatStream(sessionOpt.get(), agentOpt.get(), text, false);
    }

    /**
     * The answer to ANY approval card. The callId is the whole identity —
     * tool task, tool name and origin live in the ToolApprovalStore. No new
     * stream: the turn never ended (it is suspended on the parked tool task)
     * and its original stream carries the continuation; this delivers the
     * decision and refreshes the list so the card disappears. A STALE card
     * (no store entry any more) delivers nothing — the refresh alone drops it.
     */
    @PostMapping("/sessions/{sessionId}/approval")
    public ResponseEntity<UiPatch> approvalAnswered(@PathVariable UUID sessionId,
                                                    @RequestParam String callId,
                                                    @RequestParam boolean approved,
                                                    @RequestParam(defaultValue = "once") String scope) {
        var sessionOpt = sessionRepository.findById(sessionId);
        if (sessionOpt.isEmpty()) return ResponseEntity.notFound().build();
        var agentOpt = agentRepository.findById(sessionOpt.get().agentDefinitionId());
        if (agentOpt.isEmpty()) return ResponseEntity.notFound().build();
        boolean delivered = chatService.answerApproval(sessionId, callId, approved,
                ai.mindconnect.agent.service.approval.ApprovalScope.fromParam(scope));
        log.info("POST /admin/api/sessions/{}/approval call={} approved={} scope={} delivered={}",
                sessionId, callId, approved, scope, delivered);
        return ResponseEntity.ok(buildChatPage(sessionOpt.get(), agentOpt.get()).headerOnly());
    }

    /**
     * Regenerates the assistant response for a user message: deletes that
     * message and everything after it, then re-runs the turn (streaming) with
     * the same user text. The new user message and reply are persisted fresh.
     *
     * <p>Only meaningful on USER messages — the UI exposes the button there.
     * Sub-agent sessions spawned by the discarded turns are intentionally
     * left in place (not cleaned up).
     */
    @PostMapping(value = "/sessions/{sessionId}/messages/{seq}/regenerate",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> regenerate(@PathVariable UUID sessionId,
                                                 @PathVariable int seq) {
        var sessionOpt = sessionRepository.findById(sessionId);
        if (sessionOpt.isEmpty()) {
            var emitter = new SseEmitter();
            emitter.completeWithError(new IllegalStateException("session not found"));
            return ResponseEntity.ok(emitter);
        }
        var agentOpt = agentRepository.findById(sessionOpt.get().agentDefinitionId());
        if (agentOpt.isEmpty()) {
            var emitter = new SseEmitter();
            emitter.completeWithError(new IllegalStateException("agent not found"));
            return ResponseEntity.ok(emitter);
        }

        // Capture the user text at `seq` before we delete it.
        String text = sessionService.loadHistory(sessionId).stream()
                .filter(m -> m.sequenceNum() == seq)
                .filter(m -> m.type() == ai.mindconnect.message.domain.MessageType.CHAT)
                .filter(m -> m.senderType() == ai.mindconnect.message.domain.ParticipantType.USER)
                .map(Message::content)
                .findFirst()
                .orElse(null);
        if (text == null || text.isBlank()) {
            var emitter = new SseEmitter();
            emitter.completeWithError(new IllegalArgumentException(
                    "no user message at seq " + seq));
            return ResponseEntity.ok(emitter);
        }

        // Drop this message and everything after it; the turn below re-adds
        // the user message fresh, so deleting from `seq` inclusive avoids a
        // duplicate. toSeq = MAX_VALUE → delete to the end.
        sessionService.deleteMessages(sessionId, seq, Integer.MAX_VALUE);
        log.info("Regenerate session={} from seq={} (deleted to end, re-running turn)", sessionId, seq);

        // initialRefresh=true: push the trimmed conversation to the client
        // before streaming, so the now-deleted messages disappear from the
        // DOM instead of lingering until the end-of-turn refresh.
        return runChatStream(sessionOpt.get(), agentOpt.get(), text, true);
    }

    /**
     * Shared SSE streaming core for a chat turn: sets up the per-channel bus,
     * registers the active stream, submits {@code text} as a turn, and emits
     * UiPatch frames as tokens / task-cards / sub-agent trees arrive. Used by
     * both {@link #chatStream} and {@link #regenerate}.
     *
     * @param initialRefresh when true, a full message-list refresh is pushed
     *                       before the turn starts — used by regenerate so the
     *                       just-deleted messages leave the DOM immediately.
     */
    private ResponseEntity<SseEmitter> runChatStream(ai.mindconnect.agent.domain.AgentSession session,
                                                     ai.mindconnect.agent.domain.AgentDefinition agent,
                                                     String text, boolean initialRefresh) {
        return runTurnStream(session, agent, text, initialRefresh,
                handler -> chatService.submitChat(session.id(), text, handler));
    }

    /**
     * The streaming core, parameterised over WHAT starts the turn: a typed
     * message ({@code text} echoed as a user bubble) or an approval answer
     * ({@code text == null} — the card click is the input, nothing to echo).
     */
    private ResponseEntity<SseEmitter> runTurnStream(ai.mindconnect.agent.domain.AgentSession session,
                                                     ai.mindconnect.agent.domain.AgentDefinition agent,
                                                     String text, boolean initialRefresh,
                                                     java.util.function.Function<java.util.function.Consumer<StreamEvent>, ChatTurnHandle> turnStarter) {
        UUID sessionId = session.id();
        var emitter = new SseEmitter(0L); // no timeout — agent can take a while
        // Custom headers consumed by the client-side StreamRegistry:
        // {@code Sui-Stream-Channel} keys the live stream so a re-mount of
        // the chat page can replay buffered patches; {@code Return-Href}
        // is what the floating running-agent toast navigates to; the label
        // is what the toast displays.
        // Channel id == the id of the message-list container the patches
        // target. This way the bus's getElementById fallback in
        // {@code findStreamTarget} naturally detects "chat page mounted".
        String channelId = "msg-list-" + sessionId;
        String returnHref = "/admin/sessions/" + sessionId;
        String streamLabel = agent.name() != null ? agent.name() : "Agent";
        var streamHeaders = new HttpHeaders();
        streamHeaders.add("Sui-Stream-Channel", channelId);
        streamHeaders.add("Sui-Stream-Return-Href", returnHref);
        streamHeaders.add("Sui-Stream-Label", streamLabel);
        String pendingId  = "bot-pending-"  + sessionId;
        String thinkingId = "bot-thinking-" + sessionId;

        // The streaming-time page is built once with the pre-turn history;
        // it owns the form / message-list / task-card patch shapes the
        // event handler emits. The final refresh after the turn rebuilds
        // a fresh page from the post-turn history so the rendered list
        // reflects what's actually been persisted.
        ChatPage liveView = buildChatPage(session, agent);

        // Per-channel multiplex bus + ring buffer. The original POST
        // emitter attaches as the first subscriber; reconnect GETs from
        // /admin/api/streams/{channelId}/sse subscribe later with their
        // own emitters and replay missed events from the buffer.
        var bus = new ai.mindconnect.adminui.service.StreamBus();
        bus.attach(emitter, Long.MAX_VALUE);  // skip replay — this is the producer's own emitter

        // Register the stream so the chat-page renderer (and the generic
        // /admin/api/streams endpoint) can see "this session is streaming".
        // Cancellation goes through the same chatService.cancelChat path
        // the legacy DELETE /sessions/{id}/chat endpoint uses — no separate
        // mechanism to keep in sync.
        activeStreams.register(new ai.mindconnect.adminui.service.ActiveStreams.Handle(
                channelId,
                streamLabel,
                returnHref,
                java.time.Instant.now(),
                () -> chatService.cancelChat(sessionId),
                bus));
        // Detach this emitter from the bus when the client goes away. We
        // do NOT deregister the channel-level entry here — other
        // subscribers (reconnect tabs) may still be on it, and the
        // producer is the source of truth for "is this stream alive".
        // The whenComplete handler below removes the channel from the
        // registry once the producer is fully done.
        emitter.onCompletion(() -> bus.detach(emitter));
        emitter.onError(t -> bus.detach(emitter));
        emitter.onTimeout(() -> bus.detach(emitter));

        // 0. Regenerate only: replace the message list with the trimmed
        //    (post-delete) history so the discarded messages vanish before the
        //    new user message + reply stream in.
        if (initialRefresh) {
            publishPatch(bus, liveView.headerOnly());
        }

        // 1. Append user message (a typed turn) or just swap the form to
        //    streaming (an approval resume), add thinking indicator.
        publishPatch(bus, text != null
                ? liveView.streamStart(text, thinkingId)
                : liveView.streamResume());

        // 2. Stream tokens + per-task cards.
        StringBuilder cumulativeText = new StringBuilder();
        /** Tracks whether the bot-pending item has been appended yet. */
        final boolean[] pendingAppended = new boolean[]{false};

        // Per-task state, keyed by tool-call id (regular tools) or sub-agent
        // taskId (run_agent). Insertion order = render order in the chat.
        java.util.LinkedHashMap<String, LiveTask> liveTasks = new java.util.LinkedHashMap<>();
        /** Holds the id of the currently-open card so it can be collapsed when a new one starts. */
        final String[] openTaskNodeId = new String[]{null};
        // Maps an ephemeral run taskId → the durable sub-session id. All
        // sub-agent card/list ids are keyed on the session id, so a page
        // reload mid-run rebuilds the same nodes from persisted state and the
        // run's continuing live patches still land. Child tool events are
        // correlated only by taskId, so this is how we resolve their
        // container's session-keyed id.
        java.util.Map<java.util.UUID, java.util.UUID> taskToSession = new java.util.concurrent.ConcurrentHashMap<>();

        ChatTurnHandle turn = turnStarter.apply(event -> {
            switch (event) {
                case StreamEvent.Token t -> {
                    cumulativeText.append(t.text());
                    if (!pendingAppended[0]) {
                        // First token: drop the thinking indicator and append
                        // the streaming bot-reply placeholder BELOW any task
                        // cards that arrived during the thinking phase.
                        publishPatch(bus, liveView.streamFirstToken(pendingId, thinkingId));
                        pendingAppended[0] = true;
                    }
                    publishPatch(bus, liveView.streamToken(pendingId, cumulativeText.toString()));
                }
                case StreamEvent.ApprovalRequested ar -> {
                    // Durable already (request message / store entry written
                    // first); this is the live mirror: push the card into the
                    // open stream. Bubbled when an origin session rode along.
                    String argsJson;
                    try {
                        argsJson = ai.mindconnect.adminui.assembler.session.SessionUiCommons.MAPPER
                                .writerWithDefaultPrettyPrinter().writeValueAsString(ar.arguments());
                    } catch (Exception e) {
                        argsJson = String.valueOf(ar.arguments());
                    }
                    var card = ai.mindconnect.adminui.ui.component.MessageListComponent.approvalCard(
                            sessionId, ar.callId(), ar.toolName(), argsJson,
                            ai.mindconnect.adminui.assembler.session.SessionUiCommons.DT_FMT
                                    .format(java.time.Instant.now()));
                    publishPatch(bus, liveView.appendApprovalCard(card));
                }
                case StreamEvent.ToolCallStarted s ->
                    startToolCard(liveView, liveTasks, openTaskNodeId, bus, taskToSession,
                            null, s.toolName(), s.arguments());
                case StreamEvent.ToolCallResult r ->
                    finishToolCard(liveView, liveTasks, openTaskNodeId, bus,
                            null, r.toolName(), r.result(), r.durationMs(), false);
                case StreamEvent.ToolCallFailed f ->
                    finishToolCard(liveView, liveTasks, openTaskNodeId, bus,
                            null, f.toolName(), f.error(), f.durationMs(), true);
                case StreamEvent.SubAgentStarted s ->
                    startSubAgentCard(liveView, liveTasks, openTaskNodeId, bus, taskToSession,
                            null, s.taskId(), s.agentName(), s.subSessionId(), s.input());
                case StreamEvent.SubAgentDone sd ->
                    finishSubAgentCard(liveView, liveTasks, openTaskNodeId, bus, taskToSession,
                            sd.taskId(), sd.agentName(), sd.finalText(), null);
                case StreamEvent.SubAgentError sErr ->
                    finishSubAgentCard(liveView, liveTasks, openTaskNodeId, bus, taskToSession,
                            sErr.taskId(), sErr.agentName(), null, sErr.error());
                case StreamEvent.SubAgentEvent wrapper ->
                    handleSubAgentInner(wrapper, liveView, liveTasks, openTaskNodeId, bus, taskToSession);
                default -> {}
            }
            logEvent(event, agent.name());
        });

        // 3. When the turn completes, replace placeholders with persisted
        //    final bot message + collapsed activity, then send the done event.
        turn.result().whenComplete((response, error) -> {
            if (error != null) {
                Throwable cause = (error.getCause() != null) ? error.getCause() : error;
                log.error("SSE chat error", cause);
                // The SSE response is already committed (headers + initial events
                // flushed), so we can't surface this as an HTTP 500 — Spring's
                // exception resolver would crash on a content-type mismatch
                // ("No converter ... text/event-stream"). Instead emit a custom
                // 'error' event with a human-readable message and complete the
                // emitter normally; the browser sees a clean stream end.
                String message = cause.getMessage() != null ? cause.getMessage() : cause.toString();
                try {
                    publishPatch(bus, liveView.streamError(message));
                } catch (Exception ignored) {}
                try {
                    bus.publish("error", message);
                } catch (Exception ignored) {}
                try { bus.closeAll(); } catch (Exception ignored) {}
                try { emitter.complete(); } catch (Exception ignored) {}
                // The producer is done — drop the channel so the next page
                // render sees Send instead of Stop. Subscribers (incl.
                // reconnects) already saw the error event via the bus.
                activeStreams.deregister(channelId);
                return;
            }
            try {
                // Build a fresh page from the post-turn history so the
                // streamDone() patch reflects what's actually been persisted
                // (assistant message, historic task cards, updated tokens).
                ChatPage finalView = buildChatPage(session, agent);
                publishPatch(bus, finalView.streamDone());

                bus.publish("done", "");
                // Close every attached subscriber (incl. reconnect tabs)
                // and the original POST emitter. emitter.complete() inside
                // closeAll() is idempotent enough; explicit complete kept
                // for clarity on the legacy path.
                bus.closeAll();
                try { emitter.complete(); } catch (Exception ignored) {}
            } catch (Exception e) {
                log.error("SSE chat finalize error", e);
                emitter.completeWithError(e);
            } finally {
                // Producer fully done — drop the channel so subsequent page
                // renders show Send instead of Stop. Subscribers see the
                // final patch + done event via the bus before this fires.
                activeStreams.deregister(channelId);
            }
        });

        return ResponseEntity.ok().headers(streamHeaders).body(emitter);
    }

    /** Per-task state held while a turn is streaming. */
    private static final class LiveTask {
        final String nodeId;
        final String name;
        final boolean isSubAgent;
        /** For tasks nested inside a sub-agent: the immediate parent sub-agent's taskId. {@code null} for top-level tasks. */
        final java.util.UUID parentTaskId;
        java.util.Map<String, Object> input;   // may be null for sub-agents (no args at start)
        String output;
        long durationMs;
        boolean done;

        LiveTask(String nodeId, String name, boolean isSubAgent,
                 java.util.Map<String, Object> input, java.util.UUID parentTaskId) {
            this.nodeId = nodeId;
            this.name = name;
            this.isSubAgent = isSubAgent;
            this.input = input;
            this.parentTaskId = parentTaskId;
        }
    }

    /**
     * Unwraps a (possibly multiply-nested) {@link StreamEvent.SubAgentEvent}
     * and routes the innermost real event to the same card helpers used for
     * top-level events. The key difference from the flat-indent past: the
     * <em>immediate</em> parent sub-agent — the taskId of the innermost
     * wrapper — becomes the card's {@code parentTaskId}, so the card is
     * appended into that sub-agent's nested child list
     * ({@code subtasks-{taskId}}) and the DOM hierarchy mirrors the agent
     * call hierarchy.
     */
    private void handleSubAgentInner(StreamEvent.SubAgentEvent topWrapper,
                                      ChatPage liveView,
                                      java.util.LinkedHashMap<String, LiveTask> liveTasks,
                                      String[] openTaskNodeId,
                                      ai.mindconnect.adminui.service.StreamBus bus,
                                      java.util.Map<java.util.UUID, java.util.UUID> taskToSession) {
        // Walk to the innermost real event; the last wrapper taskId is the
        // immediate parent sub-agent that owns the event.
        java.util.UUID parentTaskId = topWrapper.taskId();
        StreamEvent inner = topWrapper.inner();
        while (inner instanceof StreamEvent.SubAgentEvent next) {
            parentTaskId = next.taskId();
            inner = next.inner();
        }

        switch (inner) {
            case StreamEvent.ToolCallStarted s ->
                startToolCard(liveView, liveTasks, openTaskNodeId, bus, taskToSession,
                        parentTaskId, s.toolName(), s.arguments());
            case StreamEvent.ToolCallResult r ->
                finishToolCard(liveView, liveTasks, openTaskNodeId, bus,
                        parentTaskId, r.toolName(), r.result(), r.durationMs(), false);
            case StreamEvent.ToolCallFailed f ->
                finishToolCard(liveView, liveTasks, openTaskNodeId, bus,
                        parentTaskId, f.toolName(), f.error(), f.durationMs(), true);
            case StreamEvent.SubAgentStarted s ->
                startSubAgentCard(liveView, liveTasks, openTaskNodeId, bus, taskToSession,
                        parentTaskId, s.taskId(), s.agentName(), s.subSessionId(), s.input());
            case StreamEvent.SubAgentDone sd ->
                finishSubAgentCard(liveView, liveTasks, openTaskNodeId, bus, taskToSession,
                        sd.taskId(), sd.agentName(), sd.finalText(), null);
            case StreamEvent.SubAgentError sErr ->
                finishSubAgentCard(liveView, liveTasks, openTaskNodeId, bus, taskToSession,
                        sErr.taskId(), sErr.agentName(), null, sErr.error());
            default -> {}
        }
    }

    // ── Live card helpers ───────────────────────────────────────────────────
    //
    // Card and list ids are keyed on the DURABLE sub-session id (resolved
    // from the ephemeral run taskId via {@code taskToSession}), so a reload
    // mid-run rebuilds the same nodes from persisted state. A card appends
    // into its parent sub-agent's nested child list when it has a parent,
    // otherwise into the top-level conversation. REPLACE (done/failed) needs
    // no target id — the card's own <li> id is morphed wherever it lives.

    /** The session-keyed nesting scope for a parent sub-agent run, or {@code null} at top level. */
    private static String scopeOf(java.util.UUID parentTaskId,
                                  java.util.Map<java.util.UUID, java.util.UUID> taskToSession) {
        if (parentTaskId == null) return null;
        java.util.UUID sid = taskToSession.get(parentTaskId);
        // Fall back to the taskId itself if the mapping is somehow missing —
        // still consistent within this live stream.
        return (sid != null ? sid : parentTaskId).toString();
    }

    private void startToolCard(ChatPage liveView,
                               java.util.LinkedHashMap<String, LiveTask> liveTasks,
                               String[] openTaskNodeId,
                               ai.mindconnect.adminui.service.StreamBus bus,
                               java.util.Map<java.util.UUID, java.util.UUID> taskToSession,
                               java.util.UUID parentTaskId, String toolName, java.util.Map<String, Object> arguments) {
        String scope = scopeOf(parentTaskId, taskToSession);
        String key  = "tool-" + (scope == null ? "top" : scope) + "-" + liveTasks.size() + "-" + toolName;
        String node = "task-" + key;
        liveTasks.put(key, new LiveTask(node, toolName, false, arguments, parentTaskId));
        openTaskNodeId[0] = null;
        var card = TaskCardComponent.runningTool(node, toolName, arguments);
        publishPatch(bus, appendCard(liveView, scope, card));
        openTaskNodeId[0] = node;
    }

    private void finishToolCard(ChatPage liveView,
                                java.util.LinkedHashMap<String, LiveTask> liveTasks,
                                String[] openTaskNodeId,
                                ai.mindconnect.adminui.service.StreamBus bus,
                                java.util.UUID parentTaskId, String toolName,
                                String resultOrError, long durationMs, boolean failed) {
        LiveTask lt = findOpenTool(liveTasks, toolName, parentTaskId);
        if (lt == null) return;
        lt.output = resultOrError;
        lt.durationMs = durationMs;
        lt.done = true;
        var card = failed
                ? TaskCardComponent.failedTool(lt.nodeId, toolName, lt.input, resultOrError, durationMs)
                : TaskCardComponent.doneTool(lt.nodeId, toolName, lt.input, resultOrError, durationMs);
        publishPatch(bus, liveView.streamTaskUpdate(card));
        if (lt.nodeId.equals(openTaskNodeId[0])) openTaskNodeId[0] = null;
    }

    private void startSubAgentCard(ChatPage liveView,
                                   java.util.LinkedHashMap<String, LiveTask> liveTasks,
                                   String[] openTaskNodeId,
                                   ai.mindconnect.adminui.service.StreamBus bus,
                                   java.util.Map<java.util.UUID, java.util.UUID> taskToSession,
                                   java.util.UUID parentTaskId, java.util.UUID taskId, String agentName,
                                   java.util.UUID subSessionId, String input) {
        // Key the card on the durable session id so a reload rebuilds the
        // identical node and continuing patches still target it.
        java.util.UUID cardKey = subSessionId != null ? subSessionId : taskId;
        if (subSessionId != null) taskToSession.put(taskId, subSessionId);
        String scope = scopeOf(parentTaskId, taskToSession);
        String node  = "task-sub-" + cardKey;
        liveTasks.put("sub-" + taskId, new LiveTask(node, agentName, true, null, parentTaskId));
        openTaskNodeId[0] = null;
        // The run_agent task message is carried on SubAgentStarted, so the
        // Input block shows immediately — like a normal tool call. The
        // open-session link uses the durable session id.
        var card = TaskCardComponent.runningSubAgent(node, agentName, cardKey.toString(),
                cardKey.toString(), input);
        publishPatch(bus, appendCard(liveView, scope, card));
        openTaskNodeId[0] = node;
    }

    private void finishSubAgentCard(ChatPage liveView,
                                    java.util.LinkedHashMap<String, LiveTask> liveTasks,
                                    String[] openTaskNodeId,
                                    ai.mindconnect.adminui.service.StreamBus bus,
                                    java.util.Map<java.util.UUID, java.util.UUID> taskToSession,
                                    java.util.UUID taskId, String agentName,
                                    String finalText, String error) {
        LiveTask lt = liveTasks.get("sub-" + taskId);
        if (lt == null) return;
        lt.done = true;
        // Don't REPLACE the whole sub-agent card — that would morph its
        // nested child <ul> back to empty and wipe the children already
        // streamed into it. Instead flip the summary marker (visible while
        // collapsed) in place and append the answer beneath the nested tree.
        java.util.UUID cardKey = taskToSession.getOrDefault(taskId, taskId);
        String tid = cardKey.toString();
        var summary = error != null
                ? TaskCardComponent.failedSubAgentSummary(tid, agentName)
                : TaskCardComponent.doneSubAgentSummary(tid, agentName, lt.durationMs);
        var answer = TaskCardComponent.subAgentAnswer(tid, error != null ? error : finalText);
        publishPatch(bus, liveView.streamSubAgentDone(summary, TaskCardComponent.stackId(tid), answer));
        if (lt.nodeId.equals(openTaskNodeId[0])) openTaskNodeId[0] = null;
    }

    /**
     * Chooses the APPEND target for a freshly-started card: the top-level
     * conversation when {@code scope} is null, otherwise the parent
     * sub-agent's (session-keyed) nested child list.
     */
    private UiPatch appendCard(ChatPage liveView, String scope, TaskCardComponent card) {
        if (scope == null) {
            return liveView.streamTaskStart(card);
        }
        return liveView.streamTaskStartInto(TaskCardComponent.childListId(scope), card);
    }

    /**
     * Finds the live tool task that hasn't completed yet for the given tool
     * name within the given parent scope. Last matching (most recently
     * inserted) wins when several are running.
     */
    private static LiveTask findOpenTool(java.util.LinkedHashMap<String, LiveTask> live,
                                          String toolName, java.util.UUID parentTaskId) {
        LiveTask candidate = null;
        for (var e : live.entrySet()) {
            LiveTask lt = e.getValue();
            if (lt.isSubAgent || lt.done) continue;
            if (!lt.name.equals(toolName)) continue;
            if (!java.util.Objects.equals(lt.parentTaskId, parentTaskId)) continue;
            candidate = lt; // keep iterating — last matching wins (most recent)
        }
        return candidate;
    }

    /**
     * Legacy single-emitter helper, kept only for the early-return paths
     * (validation failures) that close the emitter before a StreamBus is
     * created. Inside the regular streaming path the per-channel bus is
     * the publish target — see {@link #publishPatch}.
     */
    private void sendPatch(SseEmitter emitter, UiPatch patch) {
        try {
            String json = objectMapper.writeValueAsString(patch);
            emitter.send(SseEmitter.event().name("patch").data(json));
        } catch (Exception e) {
            log.warn("Failed to send SSE patch", e);
        }
    }

    /**
     * Publishes a {@code patch} event onto the channel's multiplex bus.
     * Every attached subscriber (the original POST emitter + any
     * reconnect-GET emitters from {@code /streams/{id}/sse}) sees it; the
     * ring buffer keeps the last N for late joiners.
     */
    private void publishPatch(ai.mindconnect.adminui.service.StreamBus bus, UiPatch patch) {
        try {
            String json = objectMapper.writeValueAsString(patch);
            bus.publish("patch", json);
        } catch (Exception e) {
            log.warn("Failed to publish SSE patch", e);
        }
    }

    /**
     * Best-effort working-memory snapshot. Returns {@code null} on any error
     * so the UI can still render without the token bar — never lets a stats
     * failure tear down the page.
     */
    private WorkingMemory safeMemorySnapshot(UUID sessionId) {
        try {
            return chatService.memorySnapshot(sessionId);
        } catch (Exception e) {
            log.warn("Failed to load working memory for session {}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    private void logEvent(StreamEvent event, String agentName) {
        streamLogger(agentName).accept(event);
    }

    private Consumer<StreamEvent> streamLogger(String agentName) {
        return event -> {
            switch (event) {
                case StreamEvent.AskingLlm a ->
                    log.info("asking llm");
                case StreamEvent.ToolCallStarted s ->
                    log.info("tool started: {}", s.toolName());
                case StreamEvent.ToolCallResult r ->
                    log.info("tool done: {} ({}ms)", r.toolName(), r.durationMs());
                case StreamEvent.ToolCallFailed f ->
                    log.warn("tool failed: {} ({}ms): {}", f.toolName(), f.durationMs(), f.error());
                case StreamEvent.Reviewing rv ->
                    log.info("reviewing: {}", rv.reviewerName());
                case StreamEvent.ReviewerDecision rd ->
                    log.info("reviewer decision: {} → {}", rd.reviewerName(), rd.verdict());
                case StreamEvent.ResponseRevised rev ->
                    log.info("response revised: {} blocked={}", rev.reason(), rev.blocked());
                case StreamEvent.SubAgentStarted s ->
                    log.info("sub-agent started: {} (depth {})", s.agentName(), s.depth());
                case StreamEvent.SubAgentEvent se ->
                    logSubEvent(se, 1);
                case StreamEvent.SubAgentDone sd ->
                    log.info("sub-agent done: {}", sd.agentName());
                case StreamEvent.SubAgentError sErr ->
                    log.warn("sub-agent error: {}: {}", sErr.agentName(), sErr.error());
                default -> {}
            }
        };
    }

    private void logSubEvent(StreamEvent.SubAgentEvent wrapper, int depth) {
        StreamEvent inner = wrapper.inner();
        while (inner instanceof StreamEvent.SubAgentEvent next) {
            inner = next.inner();
            depth++;
        }
        String prefix = "  ".repeat(depth) + "↳ ";
        switch (inner) {
            case StreamEvent.AskingLlm a ->
                log.info("{}asking llm", prefix);
            case StreamEvent.ToolCallStarted s ->
                log.info("{}tool started: {}", prefix, s.toolName());
            case StreamEvent.ToolCallResult r ->
                log.info("{}tool done: {} ({}ms)", prefix, r.toolName(), r.durationMs());
            case StreamEvent.ToolCallFailed f ->
                log.warn("{}tool failed: {} ({}ms): {}", prefix, f.toolName(), f.durationMs(), f.error());
            case StreamEvent.SubAgentStarted s ->
                log.info("{}sub-agent: {} (depth {})", prefix, s.agentName(), s.depth());
            case StreamEvent.SubAgentDone sd ->
                log.info("{}sub-agent done: {}", prefix, sd.agentName());
            case StreamEvent.SubAgentError sErr ->
                log.warn("{}sub-agent error: {}: {}", prefix, sErr.agentName(), sErr.error());
            default -> {}
        }
    }
}
