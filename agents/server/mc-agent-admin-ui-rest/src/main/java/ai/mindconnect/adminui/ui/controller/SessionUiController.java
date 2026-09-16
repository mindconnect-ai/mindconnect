package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.adminui.ui.page.MemoryPage;
import ai.mindconnect.adminui.ui.page.TodosPage;
import ai.mindconnect.adminui.ui.page.TracesPage;
import ai.mindconnect.adminui.ui.component.RoundtripCardComponent;
import ai.mindconnect.adminui.ui.component.TraceTableComponent;
import ai.mindconnect.agent.runtime.domain.TraceId;
import ai.mindconnect.agent.runtime.domain.view.LlmCallTraceHeader;
import ai.mindconnect.agent.runtime.memory.domain.WorkingMemory;
import ai.mindconnect.agent.runtime.port.out.LlmCallTraceRepository;
import ai.mindconnect.agent.runtime.service.SessionAgentResolver;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;
import ai.mindconnect.agent.runtime.service.AgentChatService;
import ai.mindconnect.agent.runtime.service.AgentSessionService;
import ai.mindconnect.agent.runtime.tools.todo.TodoListService;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.message.domain.ConversationId;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.agent.runtime.port.out.ToolApprovalRepository;
import ai.mindconnect.ui.model.UiDialog;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiPatch;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin/api")
public class SessionUiController {

    /** DOM id of the per-call dialog; one open at a time, re-opening replaces it. */
    static final String TRACE_DIALOG_ID = "trace-dialog";

    private static final Logger log = LoggerFactory.getLogger(SessionUiController.class);

    private final AgentSessionService sessionService;
    private final AgentChatService chatService;
    private final AgentDefinitionRepository agentRepository;
    private final AgentSessionRepository sessionRepository;
    private final TodoListService todoListService;
    private final ObjectMapper objectMapper;
    /** Optional — null in setups where trace persistence is disabled. */
    private final LlmCallTraceRepository traceRepository;
    private final ai.mindconnect.chatui.service.ActiveStreams activeStreams;

    private final ai.mindconnect.agentrest.service.SessionFileService sessionFiles;
    private final ai.mindconnect.adminui.ui.AdminLayoutFactory layoutFactory;
    private final ToolApprovalRepository approvalStore;
    /**
     * The session tools (memory, traces, todos) have to resolve the
     * agent the same way the run does. A chat with an inline session agent has
     * no entry in the registry, and looking it up there answered 404 for every
     * one of these dialogs.
     */
    private final SessionAgentResolver agentResolver;

    public SessionUiController(AgentSessionService sessionService,
                             AgentChatService chatService,
                             AgentDefinitionRepository agentRepository,
                             AgentSessionRepository sessionRepository,
                             TodoListService todoListService,
                             ObjectMapper objectMapper,
                             LlmCallTraceRepository traceRepository,
                             ai.mindconnect.chatui.service.ActiveStreams activeStreams,
                             ai.mindconnect.agentrest.service.SessionFileService sessionFiles,
                             ai.mindconnect.adminui.ui.AdminLayoutFactory layoutFactory,
                             ToolApprovalRepository approvalStore) {
        this.sessionService = sessionService;
        this.sessionFiles = sessionFiles;
        this.chatService = chatService;
        this.agentRepository = agentRepository;
        this.sessionRepository = sessionRepository;
        this.todoListService = todoListService;
        this.objectMapper = objectMapper;
        this.traceRepository = traceRepository;
        this.activeStreams = activeStreams;
        this.layoutFactory = layoutFactory;
        this.approvalStore = approvalStore;
        this.agentResolver = new SessionAgentResolver(agentRepository);
    }

    /**
     * Wraps a session tool page (memory / traces / todos / files) in a
     * wide dialog over whatever is on screen — a remove+append patch on the
     * body-level dialog host (same pattern as the tool-test dialogs). The
     * chat page underneath is never re-rendered, so scroll position and
     * stream state survive; × and backdrop close client-side.
     */
    static ResponseEntity<UiPatch> sessionDialog(SessionId sessionId, String title, UiPage inner) {
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
    private WorkingMemory safeMemorySnapshot(SessionId sessionId) {
        try {
            return chatService.memorySnapshot(sessionId);
        } catch (Exception e) {
            log.warn("Failed to load working memory for session {}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    @GetMapping("/sessions/{sessionId}/memory")
    public ResponseEntity<?> getMemory(@PathVariable("sessionId") String sessionIdValue,
                                        @RequestParam(value = "seq", required = false) Integer seq,
                                        @RequestParam(value = "dialog", defaultValue = "false") boolean dialog) {
        SessionId sessionId = SessionId.of(sessionIdValue);
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
    public ResponseEntity<?> compressMemory(@PathVariable("sessionId") String sessionIdValue) {
        SessionId sessionId = SessionId.of(sessionIdValue);
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
     * The LLM calls of the session and its sub-agents as a table — headers
     * only, the payloads are loaded per row by {@link #getTrace}. Search,
     * sort and paging re-fetch just the table via {@link #tracesTable}.
     */
    @GetMapping("/sessions/{sessionId}/traces")
    public ResponseEntity<?> getTraces(@PathVariable("sessionId") String sessionIdValue,
                                        @RequestParam(value = "dialog", defaultValue = "false") boolean dialog) {
        SessionId sessionId = SessionId.of(sessionIdValue);
        if (traceRepository == null) {
            return ResponseEntity.status(503).body("LLM call trace persistence is not enabled");
        }
        List<LlmCallTraceHeader> traces = loadTraceHeaders(sessionId);
        return sessionRepository.findById(sessionId)
                .flatMap(session -> java.util.Optional.of(agentResolver.resolve(session))
                        .map(agent -> {
                            var page = new TracesPage(session, agent, traces);
                            if (dialog) return sessionDialog(sessionId, "Traces", page.render());
                            return ResponseEntity.ok(page.render());
                        }))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * The traces table alone, for the search field, the sortable column
     * headers and the page buttons: a patch that replaces the table in
     * place, so the dialog it sits in stays open. The page buttons and
     * sort headers put {@code page}, {@code sort} and {@code dir} in the
     * query string themselves; the search field posts its form to
     * {@link #searchTraces} instead.
     */
    @GetMapping("/sessions/{sessionId}/traces/table")
    public ResponseEntity<?> tracesTable(@PathVariable("sessionId") String sessionIdValue,
                                         @RequestParam(value = "q", required = false) String q,
                                         @RequestParam(value = "page", required = false) Integer page,
                                         @RequestParam(value = "sort", required = false) String sort,
                                         @RequestParam(value = "dir", required = false) String dir) {
        if (traceRepository == null) {
            return ResponseEntity.status(503).body("LLM call trace persistence is not enabled");
        }
        SessionId sessionId = SessionId.of(sessionIdValue);
        var query = TraceTableComponent.Query.of(q, page, sort, dir);
        var table = new TraceTableComponent(sessionId, loadTraceHeaders(sessionId), query).render();
        return ResponseEntity.ok(UiPatch.of()
                .patch(UiPatch.Operation.replace(TraceTableComponent.tableId(sessionId), table)));
    }

    /** The search field posts its form here; a new search starts on page 1 and keeps the sort. */
    @PostMapping("/sessions/{sessionId}/traces/search")
    public ResponseEntity<?> searchTraces(@PathVariable("sessionId") String sessionIdValue,
                                          @RequestBody java.util.Map<String, Object> raw) {
        var form = new ai.mindconnect.chatui.ui.controller.FormBody(raw);
        return tracesTable(sessionIdValue, form.str("q"), 1, form.str("sort"), form.str("dir"));
    }

    /**
     * Walks the session tree directly via parentSessionId: top-level
     * session + every sub-agent session (transitively) it spawned. For
     * each session we know the conversationId, so we read the headers
     * straight from those known paths — no scanning every conversation
     * directory on disk.
     */
    private List<LlmCallTraceHeader> loadTraceHeaders(SessionId sessionId) {
        List<LlmCallTraceHeader> traces = new java.util.ArrayList<>();
        for (SessionId sid : collectSessionTree(sessionId)) {
            try {
                ConversationId convId = sessionRepository.findById(sid)
                        .map(s -> s.conversationId()).orElse(null);
                if (convId == null) continue;
                traces.addAll(traceRepository.findHeadersByConversation(convId));
            } catch (Exception e) {
                log.warn("Failed to load traces for session {}: {}", sid, e.getMessage());
            }
        }
        return traces;
    }

    /**
     * One LLM call in a dialog over the traces table: request, response
     * and raw event stream. The tool results shown next to the call's tool
     * calls come from the history of the session that issued the call —
     * for a sub-agent's call that is the sub-agent's session, not the one
     * in the path.
     */
    @GetMapping("/sessions/{sessionId}/traces/{traceId}")
    public ResponseEntity<?> getTrace(@PathVariable("sessionId") String sessionIdValue,
                                      @PathVariable("traceId") String traceIdValue) {
        if (traceRepository == null) {
            return ResponseEntity.status(503).body("LLM call trace persistence is not enabled");
        }
        return traceRepository.findById(TraceId.of(traceIdValue))
                .map(trace -> {
                    SessionId owner = trace.context() != null && trace.context().sessionId() != null
                            ? trace.context().sessionId() : SessionId.of(sessionIdValue);
                    List<Message> history;
                    try {
                        history = sessionService.loadHistory(owner);
                    } catch (Exception e) {
                        log.warn("Failed to load history for session {}: {}", owner, e.getMessage());
                        history = List.of();
                    }
                    var card = new RoundtripCardComponent(trace,
                            RoundtripCardComponent.indexToolResults(history));
                    var dlg = UiDialog.of(card.title(), null, card.render());
                    dlg.setId(TRACE_DIALOG_ID);
                    dlg.withCssClass("sui-dialog--wide");
                    return ResponseEntity.ok(UiPatch.of()
                            .patch(UiPatch.Operation.remove(TRACE_DIALOG_ID))
                            .patch(UiPatch.Operation.append("sui-dialogs", dlg)));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ── todos page ─────────────────────────────────────────────────────────

    /**
     * Todo-list inspector page. Shows the same checklist the LLM is being fed
     * via {@code todo_list_md} in its prompt context.
     */
    @GetMapping("/sessions/{sessionId}/todos")
    public ResponseEntity<?> getTodos(@PathVariable("sessionId") String sessionIdValue,
                                      @RequestParam(value = "dialog", defaultValue = "false") boolean dialog) {
        SessionId sessionId = SessionId.of(sessionIdValue);
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
    public ResponseEntity<?> clearTodos(@PathVariable("sessionId") String sessionIdValue) {
        todoListService.clear(SessionId.of(sessionIdValue));
        return getTodos(sessionIdValue, false);
    }

    /**
     * Walks the parent/child session tree rooted at {@code rootSessionId}
     * via {@link AgentSessionRepository#findByParentSession(SessionId)} and
     * returns every session id encountered (root first, then BFS through
     * sub-agents). Stays cheap because session.json files are tiny.
     */
    private List<SessionId> collectSessionTree(SessionId rootSessionId) {
        List<SessionId> ordered = new java.util.ArrayList<>();
        java.util.ArrayDeque<SessionId> frontier = new java.util.ArrayDeque<>();
        java.util.Set<SessionId> visited = new java.util.HashSet<>();
        frontier.add(rootSessionId);
        while (!frontier.isEmpty()) {
            SessionId current = frontier.poll();
            if (!visited.add(current)) continue;
            ordered.add(current);
            try {
                for (var sub : sessionRepository.findByParentSession(current)) {
                    frontier.add(sub.id());
                }
            } catch (Exception e) {
                log.warn("Failed to list sub-sessions of {}: {}", current, e.getMessage());
            }
        }
        return ordered;
    }
}