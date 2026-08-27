package ai.mindconnect.agent.memory.strategy;

import ai.mindconnect.message.domain.Message;
import ai.mindconnect.message.domain.MessageType;
import ai.mindconnect.message.domain.ParticipantType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the thinking-block atomicity invariant through the memory layer:
 * thinking blocks live inside the TOOL_CALL message's content JSON, so the
 * sanitizer (which treats each message atomically) must never split them from
 * their tool_use blocks. A TOOL_CALL turn is kept whole or dropped whole — never
 * halved.
 */
class ToolPairSanitizerThinkingTest {

    private static final UUID CONV = UUID.randomUUID();
    private static final UUID AGENT = UUID.randomUUID();

    private Message toolCallWithThinking(int seq, String callId) {
        // Mirrors the stored TOOL_CALL shape (LlmChatProvider.toolCallContent): thinkingBlocks
        // before toolCalls in the same content JSON.
        String json = "{"
                + "\"thinkingBlocks\":[{\"type\":\"thinking\","
                + "\"text\":\"reasoning\",\"signature\":\"SIG==\"}],"
                + "\"toolCalls\":[{\"id\":\"" + callId + "\",\"name\":\"get_weather\","
                + "\"arguments\":{\"city\":\"Berlin\"}}]}";
        return msg(seq, MessageType.TOOL_CALL, ParticipantType.AGENT, json);
    }

    private Message toolResult(int seq, String callId) {
        String json = "{\"toolCallId\":\"" + callId + "\",\"toolName\":\"get_weather\","
                + "\"result\":\"sunny\"}";
        return msg(seq, MessageType.TOOL_RESULT, ParticipantType.AGENT, json);
    }

    private Message msg(int seq, MessageType type, ParticipantType sender, String content) {
        return new Message(UUID.randomUUID(), CONV, AGENT, sender, null, type, content,
                Map.of(), seq, Instant.now(), false, null, null, null, null, null, null);
    }

    @Test
    void completeTurn_keepsThinkingBlocksUntouched() {
        Message call = toolCallWithThinking(1, "toolu_01A");
        Message result = toolResult(2, "toolu_01A");

        List<Message> out = ToolPairSanitizer.sanitize(List.of(call, result));

        // TOOL_CALL message passes through with its content (incl. thinkingBlocks) intact.
        Message keptCall = out.stream()
                .filter(m -> m.type() == MessageType.TOOL_CALL).findFirst().orElseThrow();
        assertThat(keptCall.content()).contains("\"thinkingBlocks\"");
        assertThat(keptCall.content()).contains("\"signature\":\"SIG==\"");
        assertThat(keptCall.content()).isEqualTo(call.content());
    }

    @Test
    void halfTurn_missingResult_keepsThinkingAndSynthesisesPlaceholder() {
        // TOOL_CALL with thinking but no matching TOOL_RESULT (crash-truncated /
        // window cut mid-turn). The thinking block must survive; a placeholder
        // result is spliced in so the tool pair stays symmetric.
        Message call = toolCallWithThinking(1, "toolu_01A");

        List<Message> out = ToolPairSanitizer.sanitize(List.of(call));

        Message keptCall = out.stream()
                .filter(m -> m.type() == MessageType.TOOL_CALL).findFirst().orElseThrow();
        assertThat(keptCall.content()).contains("\"thinkingBlocks\"");

        // A synthetic tool_result for the same callId was appended right after.
        long results = out.stream().filter(m -> m.type() == MessageType.TOOL_RESULT).count();
        assertThat(results).isEqualTo(1);
        assertThat(out.get(out.size() - 1).type()).isEqualTo(MessageType.TOOL_RESULT);
    }

    @Test
    void orphanResult_droppedWithoutAffectingThinkingTurn() {
        // A TOOL_RESULT whose TOOL_CALL was cut from the window is a true orphan
        // and gets dropped — it cannot leave a dangling half-turn.
        Message orphan = toolResult(1, "toolu_ORPHAN");
        Message call = toolCallWithThinking(2, "toolu_01A");
        Message result = toolResult(3, "toolu_01A");

        List<Message> out = ToolPairSanitizer.sanitize(List.of(orphan, call, result));

        boolean orphanPresent = out.stream()
                .anyMatch(m -> m.content().contains("toolu_ORPHAN"));
        assertThat(orphanPresent).isFalse();
        assertThat(out.stream().anyMatch(m -> m.content().contains("\"thinkingBlocks\""))).isTrue();
    }
}
