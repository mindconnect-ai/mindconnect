package ai.mindconnect.agent.loop.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A turn's rounds and cost, counted from the messages its model calls left. */
class TurnProgressTest {

    private static TurnMessage toolCall(String callId) {
        return TurnMessage.toolCalls(List.of(new TurnMessage.ToolCall(callId, "search", Map.of())));
    }

    @Test
    void a_turn_that_has_only_its_question_has_turned_no_round() {
        TurnProgress progress = TurnProgress.of(
                List.of(TurnMessage.user(List.of(new ContentPart.Text("hi")))));

        assertThat(progress).isEqualTo(new TurnProgress(0, Usage.ZERO));
    }

    @Test
    void every_message_with_a_cost_is_one_round_and_the_costs_add_up() {
        TurnProgress progress = TurnProgress.of(List.of(
                TurnMessage.user(List.of(new ContentPart.Text("hi"))),
                toolCall("c1").withUsage(new Usage(100, 10)),
                TurnMessage.dispatched("c1"),
                TurnMessage.toolResult("c1", "search", "found", false),
                TurnMessage.assistant("done").withUsage(new Usage(150, 20))));

        assertThat(progress).isEqualTo(new TurnProgress(2, new Usage(250, 30)));
    }

    @Test
    void a_model_call_that_cost_nothing_is_still_a_round() {
        TurnProgress progress = TurnProgress.of(List.of(TurnMessage.assistant("cached").withUsage(Usage.ZERO)));

        assertThat(progress.modelRounds()).isEqualTo(1);
    }
}
