package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.adminui.ui.component.TraceTableComponent.Query;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.runtime.domain.LlmCallTrace;
import ai.mindconnect.agent.runtime.domain.TraceContext;
import ai.mindconnect.agent.runtime.domain.TraceId;
import ai.mindconnect.message.domain.ChatTurnId;
import ai.mindconnect.message.domain.ConversationId;
import ai.mindconnect.ui.model.UiRow;
import ai.mindconnect.ui.model.UiTable;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The traces table is what an operator scans to find the one call that
 * went wrong, so this pins what a row says and where it leads: numbered
 * in the order the calls were made but listed newest first, a sub-agent's
 * call marked by its agent, a failed call by its status, every row
 * dispatching to the per-trace dialog with its own trace id — and that
 * search, sort and paging keep those numbers stable and fetch the table
 * from the right place.
 */
class TraceTableComponentTest {

    private static final SessionId SESSION = SessionId.of("11111111-1111-1111-1111-111111111111");
    private static final Instant T0 = Instant.parse("2026-09-14T10:00:00Z");

    private static LlmCallTrace call(String id, Instant at, String turn, String parentTurn,
                                     int depth, String agent, Integer errorStatus) {
        return call(id, at, turn, parentTurn, depth, agent, "gpt-x", errorStatus);
    }

    private static LlmCallTrace call(String id, Instant at, String turn, String parentTurn,
                                     int depth, String agent, String model, Integer errorStatus) {
        var ctx = new TraceContext(ConversationId.of("c1"), SESSION, ChatTurnId.of(turn),
                parentTurn == null ? null : ChatTurnId.of(parentTurn), depth, agent);
        return new LlmCallTrace(TraceId.of(id), ctx, at, 1200, "default", model,
                100, 20, errorStatus == null ? "STOP" : null, "{}", List.of(), null,
                errorStatus, errorStatus == null ? null : "boom");
    }

    private static UiTable render(LlmCallTrace... traces) {
        return new TraceTableComponent(SESSION, List.of(traces)).render();
    }

    private static UiTable render(Query query, List<LlmCallTrace> traces) {
        return new TraceTableComponent(SESSION, traces, query).render();
    }

    private static List<Object> column(UiTable table, String key) {
        return table.getRows().stream().map(r -> r.getData().get(key)).toList();
    }

    @Test
    void rowsAreNumberedInCallOrderAndListedNewestFirst() {
        var table = render(
                call("t2", T0.plusSeconds(5), "turn-a", null, 0, "Scout", null),
                call("t1", T0, "turn-a", null, 0, "Scout", null),
                call("t3", T0.plusSeconds(9), "turn-b", null, 0, "Scout", null));

        assertThat(column(table, "id")).containsExactly("t3", "t2", "t1");
        assertThat(column(table, "n")).containsExactly(3, 2, 1);
        assertThat(table.getSortColumn()).isEqualTo("n");
        assertThat(table.getSortDirection()).isEqualTo(UiTable.SortDirection.DESC);
    }

    @Test
    void aSubAgentCallShowsItsAgentAndDepthAFailedCallItsStatus() {
        var table = render(
                call("t1", T0, "turn-a", null, 0, "Scout", null),
                call("t2", T0.plusSeconds(1), "turn-a1", "turn-a", 1, "web-researcher", null),
                call("t3", T0.plusSeconds(2), "turn-a2", "turn-a1", 2, "reader", 500));

        var byId = table.getRows().stream()
                .collect(java.util.stream.Collectors.toMap(r -> r.getData().get("id"), UiRow::getData));
        assertThat(byId.get("t1").get("agent")).isEqualTo("Scout");
        assertThat(byId.get("t2").get("agent")).isEqualTo("↳ web-researcher");
        assertThat(byId.get("t3").get("agent")).isEqualTo("↳ ↳ reader");
        assertThat(byId.get("t1").get("turnId")).isEqualTo("turn-a");
        assertThat(byId.get("t1").get("status")).isEqualTo("ok");
        assertThat(byId.get("t3").get("status")).isEqualTo("✗ HTTP 500");
        assertThat(byId.get("t3").get("finish")).isEqualTo("");
    }

    @Test
    void everyRowOpensItsOwnTraceDialog() throws Exception {
        String json = new ObjectMapper().writeValueAsString(
                render(call("t1", T0, "turn-a", null, 0, "Scout", null)));

        String url = "/admin/api/sessions/" + SESSION.value() + "/traces/{id}";
        // the Details row action and the link in the number column
        assertThat(json.split(java.util.regex.Pattern.quote("\"url\":\"" + url + "\""), -1))
                .hasSize(3);
        assertThat(json).contains("\"label\":\"{n}\"");
    }

    @Test
    void searchFiltersOnWhatTheRowShowsAndOnTheFullTurnIdButKeepsTheNumbers() {
        var traces = List.of(
                call("t1", T0, "aaaaaaaa-1111", null, 0, "Scout", "gpt-x", null),
                call("t2", T0.plusSeconds(1), "bbbbbbbb-2222", "aaaaaaaa-1111", 1, "web-researcher", "claude-y", null),
                call("t3", T0.plusSeconds(2), "aaaaaaaa-1111", null, 0, "Scout", "gpt-x", 500));

        assertThat(column(render(Query.of("researcher", 1, null, null), traces), "n")).containsExactly(2);
        assertThat(column(render(Query.of("CLAUDE", 1, null, null), traces), "n")).containsExactly(2);
        assertThat(column(render(Query.of("http 500", 1, null, null), traces), "n")).containsExactly(3);
        // the turn column is shortened, the search still sees the whole id
        assertThat(column(render(Query.of("bbbb-2222", 1, null, null), traces), "n")).containsExactly(2);
        assertThat(column(render(Query.of("aaaaaaaa", 1, null, null), traces), "n")).containsExactly(3, 1);
        assertThat(render(Query.of("nothing-like-this", 1, null, null), traces).getRows()).isEmpty();
    }

    @Test
    void sortingOrdersByTheColumnWithCallOrderBreakingTies() {
        var traces = List.of(
                call("t1", T0, "turn-a", null, 0, "Scout", "gpt-x", null),
                call("t2", T0.plusSeconds(1), "turn-b", null, 0, "Scout", "claude-y", null),
                call("t3", T0.plusSeconds(2), "turn-c", null, 0, "Scout", "gpt-x", null));

        assertThat(column(render(Query.of(null, 1, "model", "asc"), traces), "n")).containsExactly(2, 1, 3);
        assertThat(column(render(Query.of(null, 1, "model", "desc"), traces), "n")).containsExactly(3, 1, 2);
        // an unknown column or direction falls back to the default
        var fallback = render(Query.of(null, 1, "bogus", "sideways"), traces);
        assertThat(column(fallback, "n")).containsExactly(3, 2, 1);
        assertThat(fallback.getSortColumn()).isEqualTo("n");
    }

    @Test
    void pagesAreSlicesOfTheSortedListAndTheButtonsCarryTheState() throws Exception {
        List<LlmCallTrace> traces = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            traces.add(call("t" + i, T0.plusSeconds(i), "turn-" + (i % 3), null, 0, "Scout", null));
        }

        var first = render(Query.of(null, 1, null, null), traces);
        assertThat(first.getRows()).hasSize(TraceTableComponent.PAGE_SIZE);
        assertThat(column(first, "n").get(0)).isEqualTo(45);
        assertThat(first.getPagination().getTotal()).isEqualTo(45);
        assertThat(first.getPagination().getPage()).isEqualTo(1);

        var last = render(Query.of(null, 3, null, null), traces);
        assertThat(last.getRows()).hasSize(5);
        assertThat(column(last, "n")).containsExactly(5, 4, 3, 2, 1);

        // a page past the end shows the last one instead of nothing
        assertThat(render(Query.of(null, 99, null, null), traces).getPagination().getPage()).isEqualTo(3);

        // a filtered, sorted page tells the buttons to keep filter and sort
        String json = new ObjectMapper().writeValueAsString(
                render(Query.of("turn-1", 1, "model", "asc"), traces));
        String base = "/admin/api/sessions/" + SESSION.value() + "/traces/table";
        assertThat(json).contains("\"url\":\"" + base + "?page={page}&sort=model&dir=asc&q=turn-1\"");
        assertThat(json).contains("\"url\":\"" + base + "?page=1&sort={column}&dir={direction}&q=turn-1\"");
        assertThat(json).contains("\"url\":\"/admin/api/sessions/" + SESSION.value() + "/traces/search\"");
    }
}
