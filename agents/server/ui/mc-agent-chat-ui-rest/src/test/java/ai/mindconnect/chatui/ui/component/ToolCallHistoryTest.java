package ai.mindconnect.chatui.ui.component;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.management.api.ManagedMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ToolCallHistoryTest {

    /** No sub-sessions resolve: the card is built from the call alone. */
    private final ToolCallHistory history = new ToolCallHistory((callId, running, input, result) -> List.of());

    @Test
    void a_finished_batch_of_sub_agents_is_headed_by_the_agents_it_ran() throws Exception {
        List<ManagedMessage> said = List.of(
                message(1, TurnMessage.toolCalls(List.of(new TurnMessage.ToolCall("c1", "run_agents",
                        Map.of("tasks", List.of(Map.of("name", "explorer", "message", "Look."),
                                Map.of("name", "url-reader", "message", "Read."))))))),
                message(2, TurnMessage.toolResult("c1", "run_agents", "## 1. explorer\nNothing.", false)));

        String cards = new ObjectMapper().writeValueAsString(
                history.buildHistoricTaskCards(said, 0, 3).stream().map(TaskCardComponent::render).toList());

        assertThat(cards).contains("explorer, url-reader").doesNotContain("sub-agent");
    }

    @Test
    void a_finished_sub_agent_is_headed_by_its_name() throws Exception {
        List<ManagedMessage> said = List.of(
                message(1, TurnMessage.toolCalls(List.of(new TurnMessage.ToolCall("c1", "run_agent",
                        Map.of("name", "explorer", "message", "Look."))))),
                message(2, TurnMessage.toolResult("c1", "run_agent", "Nothing.", false)));

        String cards = new ObjectMapper().writeValueAsString(
                history.buildHistoricTaskCards(said, 0, 3).stream().map(TaskCardComponent::render).toList());

        assertThat(cards).contains("↳ explorer").doesNotContain("sub-agent");
    }

    private static ManagedMessage message(int sequence, TurnMessage message) {
        return new ManagedMessage("m" + sequence, sequence, Instant.EPOCH, message, null, null);
    }
}
