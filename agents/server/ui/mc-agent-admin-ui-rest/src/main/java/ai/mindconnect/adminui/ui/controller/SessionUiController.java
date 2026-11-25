package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.agent.runtime.capabilities.memory.MemoryCompaction;
import ai.mindconnect.agent.runtime.capabilities.memory.MemoryInspection;
import ai.mindconnect.agent.runtime.capabilities.memory.WorkingMemory;
import ai.mindconnect.adminui.ui.page.MemoryPage;
import ai.mindconnect.adminui.ui.page.TodosPage;
import ai.mindconnect.adminui.ui.page.TracesPage;
import ai.mindconnect.adminui.ui.component.RoundtripCardComponent;
import ai.mindconnect.adminui.ui.component.TraceTableComponent;
import ai.mindconnect.agent.trace.domain.TraceId;
import ai.mindconnect.agent.trace.domain.LlmCallTraceHeader;
import ai.mindconnect.agent.trace.port.out.LlmCallTraceRepository;
import ai.mindconnect.agent.tools.todo.TodoListService;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.message.domain.ConversationId;
import ai.mindconnect.message.domain.Message;
import ai.mindconnect.common.PageRequest;
import ai.mindconnect.message.port.in.ConversationManager;
import ai.mindconnect.agent.management.api.ManagedSession;
import ai.mindconnect.agent.management.api.SessionManagement;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.store.RepositorySessionStore;
import ai.mindconnect.agent.sdk.AgentRuntime;
import ai.mindconnect.ui.model.UiDialog;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiPatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import ai.mindconnect.jackson.JsonMapperFactory;
import com.fasterxml.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/admin/api")
public class SessionUiController {

    /** DOM id of the per-call dialog; one open at a time, re-opening replaces it. */
    static final String TRACE_DIALOG_ID = "trace-dialog";

    private static final Logger log = LoggerFactory.getLogger(SessionUiController.class);

    /** The runtime's: the dialogs keep no way of their own to the sessions. */
    private final AgentRuntime runtime;
    private final SessionManagement sessions;
    private final TodoListService todoListService;
    /** Optional — null in setups where trace persistence is disabled. */
    private final LlmCallTraceRepository traceRepository;
    private final ConversationManager conversations;
    /** Writes the raw JSON the dialogs show: Jackson's defaults, as these components have always had. */
    private final ObjectMapper json;

    public SessionUiController(AgentRuntime runtime,
                             TodoListService todoListService,
                             LlmCallTraceRepository traceRepository,
                             ConversationManager conversations) {
        this.runtime = runtime;
        this.sessions = runtime.service(SessionManagement.class);
        this.todoListService = todoListService;
        this.traceRepository = traceRepository;
        this.conversations = conversations;
        this.json = runtime.service(JsonMapperFactory.class).create();
    }

    /** The agent the session runs as, for a dialog's heading; a session without one is still shown. */
    private String agentNameOf(ManagedSession session) {
        return sessions.agentOf(session.id()).map(AgentDefinition::name).orElse("(no agent)");
    }

    /** The conversation a session's messages and traces are filed under; empty for a session that is gone. */
    private java.util.Optional<ConversationId> conversationOf(SessionId sessionId) {
        return runtime.sessions().find(sessionId).map(RepositorySessionStore::conversationOf);
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

    /** What the session's agent would be sent now, or null when that cannot be told. */
    private WorkingMemory safeMemorySnapshot(SessionId sessionId) {
        try {
            return runtime.service(MemoryInspection.class).inspect(sessionId);
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
        return sessions.find(sessionId).<ResponseEntity<?>>map(session -> {
            var memory = safeMemorySnapshot(sessionId);
            if (memory == null) {
                return ResponseEntity.status(503).body("Working memory unavailable for this session");
            }
            var page = new MemoryPage(session.id(), agentNameOf(session), memory, json);
            if (seq != null) return ResponseEntity.ok(page.selectEntry(seq));
            if (dialog) return sessionDialog(sessionId, "Working Memory", page.render());
            return ResponseEntity.ok(page.render());
        }).orElse(ResponseEntity.notFound().build());
    }

    /**
     * Summarises what no summary covers yet and re-renders the
     * working-memory page, so the operator immediately sees the effect —
     * fewer tokens, a summary row. Returns a full UiPage so the front-end's
     * existing data-action handler swaps the page in place.
     */
    @PostMapping("/sessions/{sessionId}/memory/compress")
    public ResponseEntity<?> compressMemory(@PathVariable("sessionId") String sessionIdValue) {
        SessionId sessionId = SessionId.of(sessionIdValue);
        try {
            runtime.service(MemoryCompaction.class).compact(sessionId);
        } catch (Exception e) {
            log.warn("Failed to compress memory for session {}: {}", sessionId, e.getMessage());
            return ResponseEntity.status(500).body("Compression failed: " + e.getMessage());
        }
        var memory = safeMemorySnapshot(sessionId);
        if (memory == null) {
            return ResponseEntity.status(503).body("Working memory unavailable after compression");
        }
        return sessions.find(sessionId)
                .<ResponseEntity<?>>map(session ->
                        ResponseEntity.ok(new MemoryPage(session.id(), agentNameOf(session), memory, json).render()))
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
        return sessions.find(sessionId)
                .<ResponseEntity<?>>map(session -> {
                    var page = new TracesPage(sessionId, agentNameOf(session), traces);
                    if (dialog) return sessionDialog(sessionId, "Traces", page.render());
                    return ResponseEntity.ok(page.render());
                })
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
     * Walks the session tree: the session and every sub-agent session
     * (transitively) it spawned. Each session says which conversation its
     * traces are filed under, so the headers are read straight from there —
     * no scanning every conversation directory on disk.
     */
    private List<LlmCallTraceHeader> loadTraceHeaders(SessionId sessionId) {
        List<LlmCallTraceHeader> traces = new java.util.ArrayList<>();
        for (SessionId sid : collectSessionTree(sessionId)) {
            try {
                ConversationId convId = conversationOf(sid).orElse(null);
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
                        history = conversationOf(owner)
                                .map(conversation -> conversations.loadHistory(conversation,
                                        new PageRequest(0, Integer.MAX_VALUE)))
                                .orElse(List.of());
                    } catch (Exception e) {
                        log.warn("Failed to load history for session {}: {}", owner, e.getMessage());
                        history = List.of();
                    }
                    var card = new RoundtripCardComponent(trace,
                            RoundtripCardComponent.indexToolResults(history, json), json);
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
        return sessions.find(sessionId)
                .<ResponseEntity<?>>map(session -> {
                    var list = todoListService.load(sessionId);
                    var page = new TodosPage(sessionId, agentNameOf(session), list).render();
                    if (dialog) return sessionDialog(sessionId, "Todos", page);
                    return ResponseEntity.<UiPage>ok(page);
                })
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
     * and returns every session id encountered (root first, then BFS through
     * sub-agents).
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
                for (ManagedSession sub : sessions.children(current)) {
                    frontier.add(sub.id());
                }
            } catch (Exception e) {
                log.warn("Failed to list sub-sessions of {}: {}", current, e.getMessage());
            }
        }
        return ordered;
    }
}