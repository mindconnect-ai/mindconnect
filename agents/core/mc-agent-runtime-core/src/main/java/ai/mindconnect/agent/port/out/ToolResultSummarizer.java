package ai.mindconnect.agent.port.out;

/**
 * Produces a short summary of a tool result for use as a stub in the LLM context.
 * <p>
 * When a tool result is compressed, only this summary goes into the LLM message history.
 * The full result is stored externally and can be retrieved on demand.
 */
public interface ToolResultSummarizer {

    /**
     * Summarize a tool result in 2-3 sentences.
     *
     * @param toolName   the name of the tool that produced the result
     * @param fullResult the full raw tool output
     * @return a short, human-readable summary
     */
    String summarize(String toolName, String fullResult);
}
