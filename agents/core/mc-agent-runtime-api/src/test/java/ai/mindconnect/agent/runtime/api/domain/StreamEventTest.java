package ai.mindconnect.agent.runtime.api.domain;

import static org.assertj.core.api.Assertions.assertThat;

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
            return SessionEvent.of(type, SESSION, TURN, null, payload);
        }
    }

    static Stream<Told<?>> everyKind() {
        return Stream.of(
                new Told<>(StreamEvent.ASKING_LLM, new StreamEvent.AskingLlm(true)),
                new Told<>(StreamEvent.TOKEN, new StreamEvent.Token("Hel")),
                new Told<>(StreamEvent.THINKING, new StreamEvent.Thinking("hm")),
                new Told<>(StreamEvent.TOOL_CALL_STARTED,
                        new StreamEvent.ToolCallStarted("c1", "bash", Map.of("command", "ls"), 1)),
                new Told<>(StreamEvent.TOOL_CALL_RESULT, new StreamEvent.ToolCallResult("c1", "bash", "a.txt")),
                new Told<>(StreamEvent.TOOL_CALL_FAILED, new StreamEvent.ToolCallFailed("c1", "bash", "no such file")),
                new Told<>(StreamEvent.APPROVAL_REQUESTED, new StreamEvent.ApprovalRequested("i1", "c1", "bash",
                        Map.of("command", "rm"), SessionId.of("s2"))),
                new Told<>(StreamEvent.INPUT_REQUESTED, new StreamEvent.InputRequested("i2", "user_input", "c2",
                        "ask_user", Map.of("question", "Which one?"))),
                new Told<>(StreamEvent.WAITING, new StreamEvent.Waiting()),
                new Told<>(StreamEvent.TURN_USAGE, new StreamEvent.TurnUsage(1200, 340)),
                new Told<>(StreamEvent.DONE, new StreamEvent.Done(TurnStatus.INCOMPLETE, "", "MAX_ROUNDS")),
                new Told<>(StreamEvent.ERROR, new StreamEvent.Error(TurnStatus.FAILED, "The model did not answer")),
                new Told<>(StreamEvent.SUB_AGENT_STARTED, new StreamEvent.SubAgentStarted("researcher")),
                new Told<>(StreamEvent.SUB_AGENT_DONE, new StreamEvent.SubAgentDone("researcher", "Paris.")));
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
        assertThat(StreamEvent.TYPES).extracting(EventType::kind)
                .containsExactlyInAnyOrderElementsOf(Stream.concat(
                        everyKind().map(told -> told.type().kind()), Stream.of("message")).toList());
    }

    @Test
    void a_whole_message_reads_back_with_what_was_said() {
        SessionEvent event = SessionEvent.of(StreamEvent.MESSAGE, SESSION, TURN, null,
                new StreamEvent.MessageWritten(TurnMessage.assistant("Hello.")));

        assertThat(event.as(StreamEvent.MESSAGE).orElseThrow().message().text()).isEqualTo("Hello.");
    }

    @Test
    void a_kind_the_runtime_does_not_know_is_passed_on_as_it_is() {
        SessionEvent event = new SessionEvent("review.started", SESSION, TURN, null, Map.of("reviewer", "lenient"));

        assertThat(event.known()).isEqualTo(new StreamEvent.Other(event));
        assertThat(event.as(StreamEvent.TOKEN)).isEmpty();
    }

    @Test
    void an_event_of_a_turn_another_turn_started_says_so() {
        assertThat(SessionEvent.of(StreamEvent.TOKEN, SESSION, TURN, TurnId.of("t0"), new StreamEvent.Token("x"))
                .ofSubAgent()).isTrue();
        assertThat(List.of(SessionEvent.of(StreamEvent.TOKEN, SESSION, TURN, null, new StreamEvent.Token("x"))))
                .noneMatch(SessionEvent::ofSubAgent);
    }
}
