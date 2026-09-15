package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.adminui.ui.controller.SessionUiController;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.domain.view.LlmCallTraceHeader;
import ai.mindconnect.chatui.ui.UiComponent;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiColumn;
import ai.mindconnect.ui.model.UiLink;
import ai.mindconnect.ui.model.UiTable;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ai.mindconnect.ui.mvc.UiActions.ROW_ID;
import static ai.mindconnect.ui.mvc.UiActions.trigger;
import static org.springframework.web.servlet.mvc.method.annotation.MvcUriComponentsBuilder.on;

/**
 * The LLM calls of a session as a plain table — one row per provider
 * roundtrip, newest first, with the turn it belongs to, the agent that
 * issued it, model, duration, tokens and how it ended. Sub-agent calls
 * are rows like any other, marked by their agent name and nesting depth.
 *
 * <p>Every row opens the same detail dialog: the number in the first
 * column is a link and the row carries a Details action, both dispatching
 * to {@link SessionUiController#getTrace} with the row id (the trace id).
 * The table is built from headers, so opening it never reads a request
 * or response payload — those are loaded per dialog.
 */
public final class TraceTableComponent implements UiComponent {

    static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final SessionId sessionId;
    private final List<? extends LlmCallTraceHeader> traces;

    public TraceTableComponent(SessionId sessionId, List<? extends LlmCallTraceHeader> traces) {
        this.sessionId = sessionId;
        this.traces = traces;
    }

    @Override
    public String id() {
        return "trace-table-" + sessionId.value();
    }

    @Override
    public UiTable render() {
        var open = trigger(on(SessionUiController.class)
                .getTrace(sessionId.value(), ROW_ID.toString()));

        var table = UiTable.of(id(), null)
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
                .sortedBy("n", UiTable.SortDirection.DESC)
                .maxHeight("70vh");
        table.withCssClass("trace-table");

        // Number chronologically (1 = the oldest call) and list newest first,
        // so the call that just happened is at the top and the numbers still
        // read as the order in which the calls were made.
        List<? extends LlmCallTraceHeader> chronological = traces.stream()
                .sorted(Comparator.comparing(LlmCallTraceHeader::startedAt))
                .toList();
        for (int i = chronological.size() - 1; i >= 0; i--) {
            table.row(row(i + 1, chronological.get(i)));
        }
        return table;
    }

    static Map<String, Object> row(int n, LlmCallTraceHeader t) {
        var ctx = t.context();
        int depth = ctx != null ? ctx.depth() : 0;
        String agent = ctx != null && ctx.agentName() != null ? ctx.agentName() : "";
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", t.id() != null ? t.id().value() : "");
        row.put("n", n);
        row.put("time", t.startedAt() != null ? TIME_FMT.format(t.startedAt()) : "");
        row.put("turnId", ctx != null && ctx.turnId() != null ? shortId(ctx.turnId().value()) : "");
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
