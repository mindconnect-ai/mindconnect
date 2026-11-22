package ai.mindconnect.agent.loop.port.out;

import java.util.List;

import ai.mindconnect.agent.loop.domain.LlmAnswer;
import ai.mindconnect.agent.loop.domain.TurnMessage;

/**
 * The way to the model. {@code requestId} and {@code sessionId} tell the
 * implementation who it works for — which model, which system prompt, which
 * tools.
 *
 * <p>This is also the RENDERING seam: the implementation turns the message
 * history into the model's window — system prompt, tool definitions,
 * compression stubs, summaries, token budget. The loop always passes the
 * truth; what the model sees of it is decided here, fresh every round, which
 * is why a mid-turn compaction or a tool activated by a tool_search call
 * simply takes effect on the next call.
 */
public interface LlmProvider {

    /**
     * Asks the model. Blocks until the answer is complete — which can take
     * minutes, and is therefore where a {@link #cancel} must actually arrive.
     *
     * @param events where a streaming implementation reports the answer as
     *               it arrives: {@code TurnEvent.TextDelta} and
     *               {@code TurnEvent.ReasoningDelta}. The complete answer is
     *               still what this method returns.
     */
    LlmAnswer ask(String requestId, String sessionId, List<TurnMessage> history, TurnEventListener events);

    /**
     * Aborts the call of {@code requestId} that is in flight, from another
     * thread: close the live connection instead of waiting for the last
     * token. The aborted {@link #ask} returns what it has — the text so far,
     * or no message at all — and never a tool call it did not receive
     * completely. Nothing in flight: nothing to do.
     */
    void cancel(String requestId);
}
