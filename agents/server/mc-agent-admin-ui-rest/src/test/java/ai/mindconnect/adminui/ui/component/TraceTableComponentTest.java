package ai.mindconnect.adminui.ui.component;

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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The traces table is what an operator scans to find the one call that
 * went wrong, so this pins what a row says and where it leads: numbered
 * in the order the calls were made but listed newest first, a sub-agent's
 * call marked by its agent, a failed call by its status, and every row
 * dispatching to the per-trace dialog with its own trace id.
 */
class TraceTableComponentTest {

    private static final SessionId SESSION = SessionId.of("11111111-1111-1111-1111-111111111111");
    private static final Instant T0 = Instant.parse("2026-09-14T10:00:00Z");

    private static LlmCallTrace call(String id, Instant at, String turn, String parentTurn,
                                     int depth, String agent, Integer errorStatus) {
        var ctx = new TraceContext(ConversationId.of("c1"), SESSION, ChatTurnId.of(turn),
                parentTurn == null ? null : ChatTurnId.of(parentTurn), depth, agent);
        return new LlmCallTrace(TraceId.of(id), ctx, at, 1200, "default", "gpt-x",
                100, 20, errorStatus == null ? "STOP" : null, "{}", List.of(), null,
                errorStatus, errorStatus == null ? null : "boom");
    }

    private static UiTable render(LlmCallTrace... traces) {
        return new TraceTableComponent(SESSION, List.of(traces)).render();
    }

    @Test
    void rowsAreNumberedInCallOrderAndListedNewestFirst() {
        var table = render(
                call("t2", T0.plusSeconds(5), "turn-a", null, 0, "Scout", null),
                call("t1", T0, "turn-a", null, 0, "Scout", null),
                call("t3", T0.plusSeconds(9), "turn-b", null, 0, "Scout", null));

        List<UiRow> rows = table.getRows();
        assertThat(rows).extracting(r -> r.getData().get("id")).containsExactly("t3", "t2", "t1");
        assertThat(rows).extracting(r -> r.getData().get("n")).containsExactly(3, 2, 1);
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
}
