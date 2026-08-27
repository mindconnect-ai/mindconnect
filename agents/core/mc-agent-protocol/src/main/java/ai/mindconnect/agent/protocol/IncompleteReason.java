package ai.mindconnect.agent.protocol;

/** Why a response ended {@link ResponseStatus#INCOMPLETE}. */
public enum IncompleteReason {
    /** An {@code ApprovalRequest} item awaits a human decision or login. */
    WAITING_FOR_APPROVAL,
    /**
     * An open {@code FunctionCall} for a client-declared tool awaits its
     * {@code FunctionCallOutput} — the OpenAI-style mechanic: the client
     * executes the tool and sends the output as input of the next response.
     */
    WAITING_FOR_TOOL_OUTPUT,
    /** The tool-round cap was hit before the model produced a final answer. */
    MAX_ROUNDS,
    /**
     * The model was cut off at its output limit. Continuing would be wrong: a
     * function call it had started is incomplete, so nothing of that answer may
     * be executed.
     */
    MAX_OUTPUT_TOKENS,
    /** The context window could not fit the required input. */
    CONTEXT_OVERFLOW
}
