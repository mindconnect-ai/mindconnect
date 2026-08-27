package ai.mindconnect.agent.service.round;

import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageType;
import ai.mindconnect.message.domain.ParticipantType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The read model of concept 16: per-call state folded from the messages, and
 * the episode rule that keeps ancient dangling calls dead.
 */
class ToolCallsTest {

    private final UUID conversationId = UUID.randomUUID();
    private final AtomicInteger seq = new AtomicInteger();

    // ── message fixtures ────────────────────────────────────────────────────

    private Message userChat(String text) {
        return Message.of(conversationId, UUID.randomUUID(), ParticipantType.USER,
                MessageType.CHAT, text, seq.incrementAndGet());
    }

    private Message agentChat(String text) {
        return Message.of(conversationId, UUID.randomUUID(), ParticipantType.AGENT,
                MessageType.CHAT, text, seq.incrementAndGet());
    }

    private Message toolCall(String callId, String name) {
        String content = "{\"toolCalls\":[{\"id\":\"" + callId + "\",\"name\":\"" + name
                + "\",\"arguments\":{\"q\":\"x\"}}]}";
        return Message.of(conversationId, UUID.randomUUID(), ParticipantType.AGENT,
                        MessageType.TOOL_CALL, content, seq.incrementAndGet())
                .withMetadata(Map.of("callIds", List.of(callId)));
    }

    private Message dispatched(String callId) {
        return Message.of(conversationId, UUID.randomUUID(), ParticipantType.AGENT,
                        MessageType.TOOL_DISPATCHED, "", seq.incrementAndGet())
                .withMetadata(Map.of("callId", callId));
    }

    private Message toolResult(String callId) {
        return Message.of(conversationId, UUID.randomUUID(), ParticipantType.AGENT,
                        MessageType.TOOL_RESULT, "{\"result\":\"ok\"}", seq.incrementAndGet())
                .withMetadata(Map.of("callId", callId, "failed", false));
    }

    private static ToolCalls.State stateOf(ToolCalls calls, String callId) {
        return calls.byId(callId).orElseThrow().state();
    }

    // ── the state machine ───────────────────────────────────────────────────

    @Test
    void noCallsMeansAllDone() {
        ToolCalls calls = ToolCalls.of(List.of(userChat("hi"), agentChat("hello")));
        assertThat(calls.allDone()).isTrue();
        assertThat(calls.calls()).isEmpty();
    }

    @Test
    void anUnansweredCallIsRunnable() {
        ToolCalls calls = ToolCalls.of(List.of(userChat("hi"), toolCall("c1", "search")));
        assertThat(stateOf(calls, "c1")).isEqualTo(ToolCalls.State.RUNNABLE);
        ToolCalls.Call call = calls.byId("c1").orElseThrow();
        assertThat(call.name()).isEqualTo("search");
        assertThat(call.arguments()).containsEntry("q", "x");
    }

    @Test
    void aDispatchedCallIsRunningNotRunnable() {
        // The marker is what keeps a resume from starting a non-idempotent tool twice.
        ToolCalls calls = ToolCalls.of(
                List.of(userChat("hi"), toolCall("c1", "search"), dispatched("c1")));
        assertThat(stateOf(calls, "c1")).isEqualTo(ToolCalls.State.RUNNING);
    }

    @Test
    void aResultClosesTheCall() {
        ToolCalls calls = ToolCalls.of(
                List.of(userChat("hi"), toolCall("c1", "search"), dispatched("c1"), toolResult("c1")));
        assertThat(stateOf(calls, "c1")).isEqualTo(ToolCalls.State.DONE);
        assertThat(calls.allDone()).isTrue();
    }

    // ── the episode rule ────────────────────────────────────────────────────

    @Test
    void aDanglingCallFromABygoneEpisodeStaysDead() {
        // user A → call without result (crashed turn) → user B: the new user
        // message closes the old chapter — the call must NOT resurface.
        List<Message> history = List.of(
                userChat("A"), toolCall("old", "search"),
                userChat("B"));
        ToolCalls calls = ToolCalls.of(ToolCalls.episode(history));
        assertThat(calls.byId("old")).isEmpty();
        assertThat(calls.allDone()).isTrue();
    }

    @Test
    void closedHistoryBeforeTheEpisodeIsInvisibleToTheFold() {
        List<Message> history = List.of(
                userChat("A"), toolCall("done", "search"), dispatched("done"), toolResult("done"),
                agentChat("here you go"),
                userChat("B"), toolCall("fresh", "search"));
        ToolCalls calls = ToolCalls.of(ToolCalls.episode(history));
        assertThat(calls.byId("done")).isEmpty();
        assertThat(stateOf(calls, "fresh")).isEqualTo(ToolCalls.State.RUNNABLE);
    }

    // ── robustness ──────────────────────────────────────────────────────────

    @Test
    void aResultWithoutMetadataStillClosesViaContentFallback() {
        // Messages persisted before concept 16 carry the callId only in content.
        Message legacyResult = Message.of(conversationId, UUID.randomUUID(), ParticipantType.AGENT,
                MessageType.TOOL_RESULT,
                "{\"toolCallId\":\"c1\",\"toolName\":\"search\",\"result\":\"ok\"}",
                seq.incrementAndGet());
        ToolCalls calls = ToolCalls.of(
                List.of(userChat("hi"), toolCall("c1", "search"), legacyResult));
        assertThat(stateOf(calls, "c1")).isEqualTo(ToolCalls.State.DONE);
    }

    @Test
    void unreadableToolCallContentDoesNotBreakTheFold() {
        Message broken = Message.of(conversationId, UUID.randomUUID(), ParticipantType.AGENT,
                        MessageType.TOOL_CALL, "not json at all", seq.incrementAndGet())
                .withMetadata(Map.of("callIds", List.of("c1")));
        ToolCalls calls = ToolCalls.of(List.of(userChat("hi"), broken));
        assertThat(calls.calls()).isEmpty();   // logged, skipped — no exception
    }
}
