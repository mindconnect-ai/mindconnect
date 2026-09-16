package ai.mindconnect.chatui.ui.component;

import ai.mindconnect.ui.model.UiPatch;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A card on the page is updated through its summary and its body, never by
 * REPLACEing its {@code <li>} with the one-item wrapper list the component
 * renders — that morphed a whole framed list into the conversation.
 */
class TaskCardUpdateTest {

    private static String json(Object node) throws Exception {
        return new ObjectMapper().writeValueAsString(node);
    }

    @Test
    void aFinishedToolSwapsItsSummaryAndBodyNotTheListItem() throws Exception {
        var done = TaskCardComponent.doneTool("task-tool-top-0-bash", "bash",
                Map.of("command", "ls"), "a.txt", 12);

        var ops = done.updateInPlace();

        assertThat(ops).extracting(UiPatch.Operation::getTargetId)
                .containsExactly("task-tool-top-0-bash-summary", "task-tool-top-0-bash-md")
                .doesNotContain("task-tool-top-0-bash");
        assertThat(json(ops)).doesNotContain("\"type\":\"list\"").contains("bash").contains("a.txt");
    }

    /** The span a live update targets is on the card from the start. */
    @Test
    void theSummarySpanIsRenderedWithTheCard() throws Exception {
        var running = TaskCardComponent.runningTool("task-tool-top-0-bash", "bash", Map.of());

        assertThat(json(running.render())).contains("\"collapseSummaryId\":\"task-tool-top-0-bash-summary\"");
    }

    @Test
    void aSubAgentCardKeepsItsOwnSummaryId() {
        var card = new TaskCardComponent("task-sub-1", "planner", "…", true);
        assertThat(card.summaryNodeId()).isEqualTo("task-sub-1-summary");

        var sub = new TaskCardComponent("task-sub-1", "planner",
                ai.mindconnect.ui.model.UiStack.of("s"), true, TaskCardComponent.summaryId("abc"));
        assertThat(sub.summaryNodeId()).isEqualTo("subsummary-abc");
    }
}
