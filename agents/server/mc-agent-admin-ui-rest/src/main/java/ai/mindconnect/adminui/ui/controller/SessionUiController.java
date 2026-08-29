package ai.mindconnect.adminui.ui.controller;


import ai.mindconnect.chatui.ui.component.TaskCardComponent;
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
    private final ai.mindconnect.chatui.service.ActiveStreams activeStreams;

    private final ai.mindconnect.agentrest.service.SessionFileService sessionFiles;
    private final ai.mindconnect.adminui.ui.AdminLayoutFactory layoutFactory;
    private final ai.mindconnect.agent.service.approval.ToolApprovalStore approvalStore;
    /**
     * The session tools (memory, traces, todos, workspace) have to resolve the
     * agent the same way the run does. A chat with an inline session agent has
     * no entry in the registry, and looking it up there answered 404 for every
     * one of these dialogs.
     */
    private final ai.mindconnect.agent.service.SessionAgentResolver agentResolver;

    public SessionUiController(AgentSessionService sessionService,
                             AgentChatService chatService,
                             AgentDefinitionRepository agentRepository,
                             AgentSessionRepository sessionRepository,
                             TodoListService todoListService,
                             WorkspaceStore workspaceStore,
                             Namespace defaultNamespace,
                             ObjectMapper objectMapper,
                             ai.mindconnect.agent.port.out.LlmCallTraceRepository traceRepository,
                             ai.mindconnect.chatui.service.ActiveStreams activeStreams,
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
        this.agentResolver = new ai.mindconnect.agent.service.SessionAgentResolver(agentRepository);
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
     * Working-memory debug page. Without {@code seq}: full master-detail page
     * with the system prompt pre-selected. With {@code seq}: a UiPatch that
     * replaces only the detail pane (clicked from the master list).
     */

    /** Working memory, or null when the snapshot cannot be built. */
    private WorkingMemory safeMemorySnapshot(UUID sessionId) {
        try {
            return chatService.memorySnapshot(sessionId);
        } catch (Exception e) {
            log.warn("Failed to load working memory for session {}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    @GetMapping("/sessions/{sessionId}/memory")
    public ResponseEntity<?> getMemory(@PathVariable UUID sessionId,
                                        @RequestParam(value = "seq", required = false) Integer seq,
                                        @RequestParam(value = "dialog", defaultValue = "false") boolean dialog) {
        var memory = safeMemorySnapshot(sessionId);
        if (memory == null) {
            return ResponseEntity.status(503).body("Working memory unavailable for this session");
        }
        return sessionRepository.findById(sessionId)
                .flatMap(session -> java.util.Optional.of(agentResolver.resolve(session))
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
                .flatMap(session -> java.util.Optional.of(agentResolver.resolve(session))
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
                .flatMap(session -> java.util.Optional.of(agentResolver.resolve(session))
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
                .flatMap(session -> java.util.Optional.of(agentResolver.resolve(session))
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
                .flatMap(session -> java.util.Optional.of(agentResolver.resolve(session))
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
}