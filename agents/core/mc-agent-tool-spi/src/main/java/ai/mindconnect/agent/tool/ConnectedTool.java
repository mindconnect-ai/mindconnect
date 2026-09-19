package ai.mindconnect.agent.tool;

import java.util.Map;

/**
 * A tool that runs on an account the user attached.
 *
 * <p>It is handed the connection rather than looking it up. The alternative —
 * a {@link Connections} lookup in every tool — means every one of them repeats
 * the same five lines: read the parameter, fall back to the default, build the
 * sentence for the case where there is nothing to fall back to. Here that
 * happens once, in the decorator the registry wraps around this
 * ({@code ConnectionBoundTool}).
 *
 * <p>What the decorator does, so this does not have to: puts a parameter per
 * {@link ConnectionSpec#params()} into the schema — an enum of <em>this</em>
 * user's accounts — or leaves it out when there is nothing to choose; takes
 * those parameters back out of the arguments; resolves them; and answers a
 * call that has no account to run on without ever calling {@link #execute}.
 */
public interface ConnectedTool extends Tool {

    /** The tool's work, on the accounts this call was pointed at. */
    String execute(Map<String, Object> arguments, BoundConnections connections);

    /**
     * Never called by the registry: a {@code ConnectedTool} is always wrapped,
     * and the wrapper calls the two-argument form. Reachable only by a caller
     * that built the tool itself and skipped the wrapping.
     */
    @Override
    default String execute(Map<String, Object> arguments) {
        throw new IllegalStateException(name() + " needs a connection; it was resolved without one");
    }
}
