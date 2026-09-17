package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.adminui.ui.controller.SessionUiController;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.domain.view.LlmCallTraceHeader;
import ai.mindconnect.chatui.ui.UiComponent;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiColumn;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.ui.model.UiLink;
import ai.mindconnect.ui.model.UiTable;
import ai.mindconnect.ui.model.UiTrigger;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ai.mindconnect.ui.mvc.UiActions.ROW_ID;
import static ai.mindconnect.ui.mvc.UiActions.trigger;
import static org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder.on;

/**
 * The LLM calls of a session as a plain table — one row per provider
 * roundtrip with the turn it belongs to, the agent that issued it, model,
 * duration, tokens and how it ended. Sub-agent calls are rows like any
 * other, marked by their agent name and nesting depth.
 *
 * <p>Search, sort and paging happen on the server: the search field, the
 * column headers and the page buttons all fetch the table again through
 * {@link SessionUiController#tracesTable} with the state in the query
 * string, and the answer replaces only the table, so the dialog around it
 * stays. The numbers are chronological over <em>all</em> calls of the
 * session (1 = the oldest) and survive any filter or sort, so "call 17"
 * means the same thing on every page.
 *
 * <p>Every row opens the same detail dialog: the number in the first
 * column is a link and the row carries a Details action, both dispatching
 * to {@link SessionUiController#getTrace} with the row id (the trace id).
 * The turn column shows the first block of the turn id — the table is
 * for telling calls apart, the dialog names the ids in full. The table is
 * built from headers, so opening it never reads a request or response
 * payload — those are loaded per dialog.
 */
public final class TraceTableComponent implements UiComponent {

    static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    public static final int PAGE_SIZE = 20;
    static final String DEFAULT_SORT = "n";
    static final String DEFAULT_DIR = "desc";

    private static final Set<String> SORT_KEYS = Set.of("n", "time", "turnId", "agent", "model",
            "durationMs", "promptTokens", "completionTokens", "finish", "status");

    /**
     * What the viewer asked the table to show: a search text matched
     * case-insensitively against turn, agent, model, finish, status, time
     * and trace id, the page (1-based), and the sort column and direction.
     * Anything unknown falls back to the default, so a stale or hand-typed
     * query string never breaks the table.
     */
    public record Query(String q, int page, String sort, String dir) {

        public static final Query DEFAULT = new Query(null, 1, DEFAULT_SORT, DEFAULT_DIR);

        public static Query of(String q, Integer page, String sort, String dir) {
            return new Query(
                    q == null || q.isBlank() ? null : q.trim(),
                    page == null || page < 1 ? 1 : page,
                    sort != null && SORT_KEYS.contains(sort) ? sort : DEFAULT_SORT,
                    "asc".equalsIgnoreCase(dir) ? "asc" : DEFAULT_DIR);
        }
    }

    private final SessionId sessionId;
    private final List<? extends LlmCallTraceHeader> traces;
    private final Query query;

    public TraceTableComponent(SessionId sessionId, List<? extends LlmCallTraceHeader> traces) {
        this(sessionId, traces, Query.DEFAULT);
    }

    public TraceTableComponent(SessionId sessionId, List<? extends LlmCallTraceHeader> traces, Query query) {
        this.sessionId = sessionId;
        this.traces = traces;
        this.query = query == null ? Query.DEFAULT : query;
    }

    @Override
    public String id() {
        return tableId(sessionId);
    }

    public static String tableId(SessionId sessionId) {
        return "trace-table-" + sessionId.value();
    }

    @Override
    public UiTable render() {
        var open = trigger(on(SessionUiController.class)
                .getTrace(sessionId.value(), ROW_ID.toString()));

        var table = UiTable.of(id(), null).stackOnMobile(true)
                .headerExtra(searchForm())
                .column(UiColumn.number("n", "#").asSortable()
                        .withCellTemplate(UiLink.of("trace-open", "#", "{n}").onClick(open)))
                .column(UiColumn.text("time", "Time").asSortable())
                .column(UiColumn.text("turnId", "Turn").asSortable())
                .column(UiColumn.text("agent", "Agent").asSortable())
                .column(UiColumn.text("model", "Model").asSortable())
                .column(UiColumn.number("durationMs", "ms").asSortable())
                .column(UiColumn.number("promptTokens", "In").asSortable())
                .column(UiColumn.number("completionTokens", "Out").asSortable())
                .column(UiColumn.text("finish", "Finish").asSortable())
                .column(UiColumn.text("status", "Status").asSortable())
                .rowAction(UiAction.icon("details", "Details").icon("show").onClick(open))
                .sortTrigger(UiTrigger.api("GET", tableUrl(sessionId, query.q(), "1", "{column}", "{direction}")))
                .sortedBy(query.sort(), "asc".equals(query.dir())
                        ? UiTable.SortDirection.ASC : UiTable.SortDirection.DESC)
                .maxHeight("60vh");
        table.withCssClass("trace-table");

        List<Map<String, Object>> rows = rows(traces, query);
        int total = rows.size();
        int pages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.min(query.page(), pages);
        int from = (page - 1) * PAGE_SIZE;
        for (Map<String, Object> row : rows.subList(from, Math.min(from + PAGE_SIZE, total))) {
            table.row(row);
        }
        table.paginate(page, PAGE_SIZE, total,
                UiTrigger.api("GET", tableUrl(sessionId, query.q(), "{page}", query.sort(), query.dir())));
        return table;
    }

    /**
     * The search field posts its form to {@link SessionUiController#searchTraces};
     * the hidden fields carry the sort along so a search keeps the order the
     * viewer chose and only resets the page.
     */
    private UiForm searchForm() {
        String formId = "trace-search-" + sessionId.value();
        UiForm form = UiForm.of(formId, null);
        form.field(UiField.hidden("sort", query.sort()));
        form.field(UiField.hidden("dir", query.dir()));
        form.field(UiField.text("q", "", query.q())
                .asEditable()
                .icon("search")
                .placeholder("Search turn, agent, model, finish…")
                .onChange(trigger(on(SessionUiController.class).searchTraces(sessionId.value(), null), formId)));
        return form;
    }

    /**
     * The URL the table fetches itself from. {@code page}, {@code sort} and
     * {@code dir} may be the client's literal placeholders ({@code {page}},
     * {@code {column}}, {@code {direction}}), which is why this is string
     * work rather than an {@code on(...)} call: the builder would encode
     * the braces.
     */
    static String tableUrl(SessionId sessionId, String q, String page, String sort, String dir) {
        return "/admin/api/sessions/" + sessionId.value() + "/traces/table"
                + "?page=" + page + "&sort=" + sort + "&dir=" + dir
                + (q == null ? "" : "&q=" + URLEncoder.encode(q, StandardCharsets.UTF_8));
    }

    /**
     * All rows of the session — numbered chronologically over the whole
     * set, then filtered by the search text and sorted as asked. Paging is
     * the caller's slice of this list.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static List<Map<String, Object>> rows(List<? extends LlmCallTraceHeader> traces, Query query) {
        List<? extends LlmCallTraceHeader> chronological = traces.stream()
                .sorted(Comparator.comparing(LlmCallTraceHeader::startedAt))
                .toList();
        List<Map<String, Object>> rows = new ArrayList<>();
        String needle = query.q() == null ? null : query.q().toLowerCase();
        for (int i = 0; i < chronological.size(); i++) {
            Map<String, Object> row = row(i + 1, chronological.get(i));
            if (needle == null || matches(row, needle)) rows.add(row);
        }
        Comparator<Map<String, Object>> order = Comparator.comparing(
                r -> (Comparable) r.get(query.sort()),
                Comparator.nullsFirst(Comparator.naturalOrder()));
        // Ties (same model, same finish, …) keep call order, so a sorted
        // page still reads chronologically within one value.
        order = order.thenComparing(r -> (Integer) r.get("n"));
        if (!"asc".equals(query.dir())) order = order.reversed();
        rows.sort(order);
        return rows;
    }

    private static boolean matches(Map<String, Object> row, String needle) {
        for (String key : List.of("turnId", "turnIdFull", "agent", "model", "finish", "status", "time", "id")) {
            Object v = row.get(key);
            if (v != null && v.toString().toLowerCase().contains(needle)) return true;
        }
        return false;
    }

    static Map<String, Object> row(int n, LlmCallTraceHeader t) {
        var ctx = t.context();
        int depth = ctx != null ? ctx.depth() : 0;
        String agent = ctx != null && ctx.agentName() != null ? ctx.agentName() : "";
        String turnId = ctx != null && ctx.turnId() != null ? ctx.turnId().value() : "";
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", t.id() != null ? t.id().value() : "");
        row.put("n", n);
        row.put("time", t.startedAt() != null ? TIME_FMT.format(t.startedAt()) : "");
        row.put("turnId", shortId(turnId));
        // Searchable in full, shown short.
        row.put("turnIdFull", turnId);
        row.put("agent", depth > 0 ? "↳ ".repeat(depth) + agent : agent);
        row.put("model", t.modelName() != null ? t.modelName() : "");
        row.put("durationMs", t.durationMs());
        row.put("promptTokens", t.promptTokens());
        row.put("completionTokens", t.completionTokens());
        row.put("finish", t.finishReason() != null ? t.finishReason() : "");
        row.put("status", t.errorStatus() != null ? "✗ HTTP " + t.errorStatus() : "ok");
        return row;
    }

    /**
     * The first block of a UUID. Enough to tell the turns of one session
     * apart and to match the calls of one turn by eye; the full id is in
     * the call's dialog.
     */
    static String shortId(String id) {
        return id.length() > 8 ? id.substring(0, 8) : id;
    }
}
