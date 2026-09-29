package ai.mindconnect.agent.tool;

import java.util.List;
import java.util.Map;

/**
 * Runs one of the caller's sibling tools, in the caller's own scope — the
 * host side of "a tool that calls tools".
 *
 * <p>Written for {@code code_execute}, whose sandboxed program asks the host
 * to run a tool instead of returning to the model for every call: the calls
 * happen inside one script, and only what the script prints reaches the
 * model's context. The sandbox therefore needs no user, no session and no
 * credentials — it names a tool, and the invoker resolves it against the
 * {@link ToolCallScope} the runtime already holds.
 *
 * <p>What the runtime guarantees on this path is what it guarantees for a
 * model-issued call: the same toolset the agent has in this session (user
 * tools and search activations included), the same advisor chain, the same
 * error text. What it does NOT do is write anything to the conversation —
 * an inner call is not a {@code TOOL_CALL}, and its result is not a
 * {@code TOOL_RESULT}. The calling tool reports them as metadata on its own
 * result; that is the whole point of the detour.
 *
 * <p><b>Approval is not this port's business.</b> The gate sits where it
 * always did, in front of the outer call, and a caller that lets a program
 * reach freely into the toolset must have been approved for that. See the
 * runtime's tool task.
 */
public interface ScopedToolInvoker {

    /**
     * Runs {@code toolName} with {@code arguments} in {@code scope}.
     *
     * <p>Never throws: a tool that is unknown, not callable or blows up comes
     * back as a failed {@link Result} whose text explains it, exactly as the
     * model would be told. {@code callerToolName} is the tool asking — it
     * cannot call itself, whatever the program says.
     */
    Result invoke(ToolCallScope scope, String callerToolName, String toolName, Map<String, Object> arguments);

    /**
     * The tools {@code callerToolName} may run in this scope, by name and
     * sorted — for validating what a program declares, and for telling the
     * model what its code can reach. Empty outside a session.
     */
    List<String> callableTools(ToolCallScope scope, String callerToolName);

    /**
     * @param output     what the model would see: the tool's result, or the error text
     * @param failed     the tool was unknown, refused or failed
     * @param durationMs wall clock of the call
     */
    record Result(String output, boolean failed, long durationMs) {

        public static Result failure(String message) {
            return new Result(message, true, 0L);
        }
    }
}
