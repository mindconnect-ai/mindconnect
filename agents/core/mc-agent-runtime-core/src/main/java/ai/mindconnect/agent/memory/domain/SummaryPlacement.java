package ai.mindconnect.agent.memory.domain;

/**
 * Where to inject persisted conversation summaries when building the LLM request.
 * <p>
 * Both placements show the same content; the difference is structural and affects
 * how the model interprets it, and how prompt-caching behaves.
 */
public enum SummaryPlacement {

    /**
     * Append to the system prompt under "## Earlier conversation (summarized)".
     * <p>
     * Pros: clean separation between instructions and dialog; participates in
     * any system-prompt prompt-cache prefix.
     * Cons: the model treats it as part of its instructions, which can feel less
     * conversational.
     */
    SYSTEM_PROMPT,

    /**
     * Prepend a synthetic user message before the live window:
     * "This session is being continued from a previous conversation that ran out
     * of context. The summary below covers the earlier portion of the conversation."
     * <p>
     * This is how Claude Code does it. The model treats the summary as something
     * the user just told it, which often produces more natural follow-up.
     * Cons: invalidates the prompt-cache for the conversation suffix on each compress.
     */
    USER_MESSAGE
}
