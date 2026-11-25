package ai.mindconnect.agent.runtime.api.domain;

import static org.assertj.core.api.Assertions.assertThat;
import ai.mindconnect.agent.loop.domain.TurnInterruptionEvent;
import ai.mindconnect.agent.loop.domain.TurnStreamEvent;
import ai.mindconnect.agent.loop.domain.StreamEvent;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** An event is a kind and plain values; what the runtime tells itself reads back as what was told. */
class StreamEventTest {

    private static final SessionId SESSION = SessionId.of("s1");
    private static final TurnId TURN = TurnId.of("t1");

    private record Told<T extends StreamEvent>(EventType<T> type, T payload) {
        SessionEvent event() {
            return SessionEvent.of(SESSION, TURN, null, payload);
        }
    }

    static Stream<Told<?>> everyKind() {
        return Stream.of(
                new Told<>(EventType.ASKING_LLM, new TurnStreamEvent.AskingLlm()),
                new Told<>(EventType.TEXT_DELTA, new TurnStreamEvent.TextDelta("Hel")),
                new Told<>(EventType.THINKING, new TurnStreamEvent.Thinking("hm")),
                new Told<>(EventType.TOOL_CALL_STARTED,
                        new TurnStreamEvent.ToolCallStarted("c1", "bash", Map.of("command", "ls"), 1)),
                new Told<>(EventType.TOOL_CALL_RESULT, new TurnStreamEvent.ToolCallResult("c1", "bash", "a.txt")),
                new Told<>(EventType.TOOL_CALL_FAILED, new TurnStreamEvent.ToolCallFailed("c1", "bash", "no such file")),
                new Told<>(EventType.APPROVAL_REQUESTED, new TurnInterruptionEvent.ApprovalRequested("i1", "c1", "bash",
                        Map.of("command", "rm"), "s2")),
                new Told<>(EventType.INPUT_REQUESTED, new TurnInterruptionEvent.InputRequested("i2", "user_input", "c2",
                        "ask_user", Map.of("question", "Which one?"))),
                new Told<>(EventType.WAITING, new SessionStreamEvent.Waiting()),
                new Told<>(EventType.TURN_USAGE, new TurnStreamEvent.TurnUsage(1200, 340)),
                new Told<>(EventType.DONE, new SessionStreamEvent.Done(TurnStatus.INCOMPLETE, "", "MAX_ROUNDS")),
                new Told<>(EventType.ERROR, new SessionStreamEvent.Error(TurnStatus.FAILED, "The model did not answer")),
                new Told<>(EventType.SUB_AGENT_STARTED, new SessionStreamEvent.SubAgentStarted("researcher")),
                new Told<>(EventType.SUB_AGENT_DONE, new SessionStreamEvent.SubAgentDone("researcher", "Paris.")));
    }

    @ParameterizedTest
    @MethodSource("everyKind")
    void a_kind_of_the_runtime_reads_back_as_what_was_told(Told<?> told) {
        assertThat(told.event().known()).isEqualTo(told.payload());
        assertThat(told.event().kind()).isEqualTo(told.type().kind());
    }

    @Test
    void the_list_of_kinds_is_the_one_the_test_knows() {
        // A kind that is added gets a line above, so that it is read back too.
        assertThat(EventType.TYPES).extracting(EventType::kind)
                .containsExactlyInAnyOrderElementsOf(Stream.concat(
                        everyKind().map(told -> told.type().kind()), Stream.of("message")).toList());
    }

    @Test
    void a_whole_message_reads_back_with_what_was_said() {
        SessionEvent event = SessionEvent.of(SESSION, TURN, null,
                new TurnStreamEvent.MessageWritten(TurnMessage.assistant("Hello.")));

        assertThat(event.as(EventType.MESSAGE).orElseThrow().message().text()).isEqualTo("Hello.");
    }

    @Test
    void a_kind_the_runtime_does_not_know_is_passed_on_as_it_is() {
        SessionEvent event = new SessionEvent("review.started", SESSION, TURN, null, Map.of("reviewer", "lenient"));

        assertThat(event.known()).isEqualTo(new SessionStreamEvent.Other(event));
        assertThat(event.as(EventType.TEXT_DELTA)).isEmpty();
    }

    @Test
    void an_event_of_a_turn_another_turn_started_says_so() {
        assertThat(SessionEvent.of(SESSION, TURN, TurnId.of("t0"), new TurnStreamEvent.TextDelta("x"))
                .ofSubAgent()).isTrue();
        assertThat(List.of(SessionEvent.of(SESSION, TURN, null, new TurnStreamEvent.TextDelta("x"))))
                .noneMatch(SessionEvent::ofSubAgent);
    }
}
