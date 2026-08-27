package ai.mindconnect.agent.service.round;

import ai.mindconnect.common.Cancellation;
import ai.mindconnect.llm.domain.ToolDefinition;
import ai.mindconnect.message.domain.Message;

import java.util.List;
import java.util.UUID;

/**
 * The way to the model. {@code requestId} and {@code sessionId} tell the
 * implementation who it works for — which model, which system prompt, and
 * where to stream. The loop has no delta sink: fragments of a running call are
 * none of its business; the implementation publishes them on the channel of
 * the requestId.
 *
 * <p>This is also the RENDERING seam (concept 16): the implementation turns
 * the message history into the model's window — system prompt, compression
 * stubs, summaries, token budget. The loop always passes the truth; what the
 * model sees of it is decided here, fresh every round, which is why a
 * mid-turn compaction simply takes effect on the next call.
 */
public interface LlmProvider {

    /**
     * Asks the model. Blocks until the answer is complete — which can take
     * minutes, and is therefore where a cancel must actually arrive:
     * {@code cancellation} is the same handle the loop polls between rounds;
     * the implementation registers its abort hook on it and closes the live
     * connection instead of waiting for the last token.
     */
    LlmAnswer ask(String requestId, UUID sessionId, List<Message> history,
                  List<ToolDefinition> toolDefinitions, Cancellation cancellation);
}
