package ai.mindconnect.agent.loop.domain;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The per-call state folded from the messages, and the episode rule that
 * keeps ancient dangling calls dead.
 */
class ToolCallsTest {

    // ── message fixtures ────────────────────────────────────────────────────

    private static TurnMessage user(String text, String turnId) {
        return new TurnMessage(null, turnId, TurnMessage.Type.USER,
                List.of(new ContentPart.Text(text)), List.of(), Map.of());
    }

    private static TurnMessage user(String text) {
        return user(text, null);
    }

    private static TurnMessage toolCall(String callId, String name) {
        return TurnMessage.toolCalls(List.of(new TurnMessage.ToolCall(callId, name, Map.of("q", "x"))));
    }

    private static TurnMessage toolResult(String callId) {
        return TurnMessage.toolResult(callId, "search", "ok", false);
    }

    private static ToolCalls.State stateOf(ToolCalls calls, String callId) {
        return calls.byId(callId).orElseThrow().state();
    }

    // ── the state machine ───────────────────────────────────────────────────

    @Test
    void no_calls_means_all_done() {
        ToolCalls calls = ToolCalls.of(List.of(user("hi"), TurnMessage.assistant("hello")));

        assertThat(calls.allDone()).isTrue();
        assertThat(calls.calls()).isEmpty();
    }

    @Test
    void an_unanswered_call_is_runnable_and_carries_its_name_and_arguments() {
        ToolCalls calls = ToolCalls.of(List.of(user("hi"), toolCall("c1", "search")));

        assertThat(stateOf(calls, "c1")).isEqualTo(ToolCalls.State.RUNNABLE);
        ToolCalls.Call call = calls.byId("c1").orElseThrow();
        assertThat(call.name()).isEqualTo("search");
        assertThat(call.arguments()).containsEntry("q", "x");
    }

    @Test
    void a_dispatched_call_is_running_not_runnable() {
        // The marker is what keeps a resume from starting a non-idempotent tool twice.
        ToolCalls calls = ToolCalls.of(
                List.of(user("hi"), toolCall("c1", "search"), TurnMessage.dispatched("c1")));

        assertThat(stateOf(calls, "c1")).isEqualTo(ToolCalls.State.RUNNING);
    }

    @Test
    void a_result_closes_the_call() {
        ToolCalls calls = ToolCalls.of(List.of(
                user("hi"), toolCall("c1", "search"), TurnMessage.dispatched("c1"), toolResult("c1")));

        assertThat(stateOf(calls, "c1")).isEqualTo(ToolCalls.State.DONE);
        assertThat(calls.allDone()).isTrue();
    }

    @Test
    void several_calls_of_one_message_are_tracked_apart() {
        TurnMessage twoCalls = TurnMessage.toolCalls(List.of(
                new TurnMessage.ToolCall("c1", "search", Map.of()),
                new TurnMessage.ToolCall("c2", "fetch", Map.of())));

        ToolCalls calls = ToolCalls.of(List.of(user("hi"), twoCalls, toolResult("c1")));

        assertThat(twoCalls.metadata()).containsEntry("callIds", List.of("c1", "c2"));
        assertThat(stateOf(calls, "c1")).isEqualTo(ToolCalls.State.DONE);
        assertThat(stateOf(calls, "c2")).isEqualTo(ToolCalls.State.RUNNABLE);
    }

    @Test
    void a_null_argument_value_survives_into_the_call() {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("limit", null);

        ToolCalls calls = ToolCalls.of(List.of(user("hi"),
                TurnMessage.toolCalls(List.of(new TurnMessage.ToolCall("c1", "search", arguments)))));

        assertThat(calls.byId("c1").orElseThrow().arguments()).containsEntry("limit", null);
    }

    // ── the episode rule ────────────────────────────────────────────────────

    @Test
    void a_dangling_call_from_a_bygone_episode_stays_dead() {
        // user A → call without result (crashed turn) → user B: the new user
        // message closes the old chapter — the call must NOT resurface.
        List<TurnMessage> history = List.of(user("A"), toolCall("old", "search"), user("B"));

        ToolCalls calls = ToolCalls.of(ToolCalls.episode(history));

        assertThat(calls.byId("old")).isEmpty();
        assertThat(calls.allDone()).isTrue();
    }

    @Test
    void closed_history_before_the_episode_is_invisible_to_the_fold() {
        List<TurnMessage> history = List.of(
                user("A"), toolCall("done", "search"), TurnMessage.dispatched("done"), toolResult("done"),
                TurnMessage.assistant("here you go"),
                user("B"), toolCall("fresh", "search"));

        ToolCalls calls = ToolCalls.of(ToolCalls.episode(history));

        assertThat(calls.byId("done")).isEmpty();
        assertThat(stateOf(calls, "fresh")).isEqualTo(ToolCalls.State.RUNNABLE);
    }

    @Test
    void a_user_message_of_the_open_turn_does_not_start_a_new_episode() {
        // The runtime inserts a user message within a turn (an attachment
        // shown again): the call before it is still this turn's to run.
        List<TurnMessage> history = List.of(
                user("A", "turn-1"), toolCall("c1", "show"), user("attachment again", "turn-1"));

        ToolCalls calls = ToolCalls.of(ToolCalls.episode(history));

        assertThat(stateOf(calls, "c1")).isEqualTo(ToolCalls.State.RUNNABLE);
    }

    @Test
    void a_user_message_of_another_turn_starts_a_new_episode() {
        List<TurnMessage> history = List.of(
                user("A", "turn-1"), toolCall("old", "search"), user("B", "turn-2"));

        assertThat(ToolCalls.episode(history)).hasSize(1);
        assertThat(ToolCalls.of(ToolCalls.episode(history)).byId("old")).isEmpty();
    }

    @Test
    void a_history_without_a_user_message_is_one_episode() {
        List<TurnMessage> history = List.of(toolCall("c1", "search"));

        assertThat(ToolCalls.episode(history)).isEqualTo(history);
    }
}
