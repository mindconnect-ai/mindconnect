package ai.mindconnect.agent.runtime.capabilities.memory;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import ai.mindconnect.agent.loop.domain.TurnMessage;

/**
 * What a session's agent would be sent if a turn asked the model now: its
 * instructions, the summary of what came before, and of the messages said
 * which ones go along and how. For whoever wants to see what the model
 * works from — and how full its context is — without running a turn.
 *
 * @param instructions        as the model reads them: the template rendered
 *                            and what the capabilities add to it
 * @param summary             what stands in for the messages that are not
 *                            sent any more; empty when nothing was summarised
 * @param messages            every message of the session, in order
 * @param totalTokens         instructions, summary and the messages that are
 *                            sent, together
 * @param contextWindowTokens what the agent's model can hold; empty when the
 *                            gateway does not know
 */
public record WorkingMemory(
        String instructions,
        int instructionTokens,
        Optional<String> summary,
        List<Entry> messages,
        int totalTokens,
        OptionalInt contextWindowTokens
) {

    public WorkingMemory {
        messages = List.copyOf(messages);
    }

    /**
     * @param sent   whether the message goes to the model: one a summary
     *               covers or the agent's memory setting leaves out does not
     * @param sentAs the note the model reads in place of the message; null
     *               when it reads the message as it was said
     * @param tokens of what the model reads; of the message itself for one
     *               that is not sent
     */
    public record Entry(TurnMessage message, boolean sent, String sentAs, int tokens) { }
}
