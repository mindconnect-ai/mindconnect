package ai.mindconnect.agent.memory.port.in;

import ai.mindconnect.agent.port.out.TokenCounter;
import ai.mindconnect.agent.domain.AgentDefinition;
import ai.mindconnect.agent.domain.AgentSession;
import ai.mindconnect.agent.memory.domain.WorkingMemory;
import ai.mindconnect.common.AuthenticationInfo;
import ai.mindconnect.llm.domain.LlmMessage;
import ai.mindconnect.message.domain.Message;

import java.util.List;

/**
 * A memory strategy decides — for one specific {@link AgentDefinition} — how the
 * conversation history is reflected in each LLM request: what goes into the live
 * window, when/how it is compressed, and what the system-prompt addendum looks like.
 * <p>
 * One implementation per {@link ai.mindconnect.agent.memory.domain.MemoryConfig} subtype.
 * Instances are obtained from the {@link MemoryStrategyFactory}.
 */
public interface MemoryStrategy {

    /** Stable name for logging and /memory display. */
    String kind();

    /** LLM-ready messages for the live window (system prompt is built separately). */
    List<LlmMessage> buildWindow(AgentDefinition def, AgentSession session, AuthenticationInfo auth);

    /**
     * Same window, rendered from an ALREADY LOADED history — the turn loop
     * holds the full message list in memory and renders per model round;
     * without this overload every round re-reads the whole conversation from
     * the store. The default ignores the list and loads (correct, slow);
     * strategies override it to render from the passed truth.
     */
    default List<LlmMessage> buildWindow(AgentDefinition def, AgentSession session,
                                         AuthenticationInfo auth,
                                         List<Message> history) {
        return buildWindow(def, session, auth);
    }

    /**
     * Returns the per-entry view of the live window — one
     * {@link WorkingMemory.WorkingMemoryMessage} per item the LLM will see
     * (including persisted summaries when the strategy injects them).
     * Backs the {@code /memory} endpoint and the working-memory snapshot
     * persisted after each turn.
     */
    List<WorkingMemory.WorkingMemoryMessage> getWindowMessages(AgentDefinition def, AgentSession session);

    /** Optional addendum appended to the agent's system prompt (e.g. compressed summaries). Empty string if none. */
    String systemPromptAddendum(AgentDefinition def, AgentSession session);

    /**
     * Hook fired at the START of every turn execution (first run and every
     * wake), after the conversation was reloaded and BEFORE the round renders
     * its window — the one moment that always runs and always sees the whole
     * fresh history in the queue architecture (concept 16). Strategies that
     * compress tool results mark eligible ones here ({@code withCompressed} —
     * the original stays in {@code content}, only the rendered window
     * shrinks); others no-op.
     *
     * <p>Eligible follows the Claude model: never a result the LLM has not
     * read yet (no assistant message after it — that is the result the wake
     * is about), never the most recent few results, and only under window
     * pressure.
     *
     * @param history the COMPLETE conversation, sequence order
     * @return how many results were marked — 0 lets the caller keep its
     *         already-loaded history instead of reloading
     */
    default int compressEligibleToolResults(AgentDefinition def, AgentSession session,
                                            AuthenticationInfo auth, List<Message> history) {
        return 0;   // no-op by default
    }

    /**
     * Hook fired after a full chat turn has been persisted (user msg + agent reply
     * + tool messages). Strategies may use this to trigger auto-summarization once
     * the live window crosses a configured threshold.
     */
    default void onAfterTurn(AgentDefinition def, AgentSession session, AuthenticationInfo auth) {
        // no-op by default
    }

    /** Called by {@code /compress}; may be a no-op for strategies without summarization. */
    CompressResult compress(AgentDefinition def, AgentSession session, AuthenticationInfo auth);

    /** Resolves the token counter the strategy uses for accounting. */
    TokenCounter resolveTokenCounter(AgentDefinition def);

    /** Total LLM context window in tokens (or {@code null} if unknown). */
    Integer contextWindowTokens(AgentDefinition def);

    record CompressResult(int compressedMessages) {
        public boolean isEmpty() { return compressedMessages == 0; }
        public static CompressResult empty() { return new CompressResult(0); }
    }
}
