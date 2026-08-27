package ai.mindconnect.agent.service.round;

import java.util.List;

/**
 * What came back from one model call. Several messages, not one: a TOOL_CALL
 * message and a CHAT answer can arise from a single call.
 *
 * @param messages  the answer, in the order it came
 * @param usage     token usage of this one call; the turn sums
 * @param truncated the model was cut off at the output limit — the answer is
 *                  incomplete, and a half-emitted tool call must not run
 */
public record LlmAnswer(List<TurnMessage> messages, Usage usage, boolean truncated) {

    public static LlmAnswer of(List<TurnMessage> messages, Usage usage) {
        return new LlmAnswer(messages, usage, false);
    }
}
