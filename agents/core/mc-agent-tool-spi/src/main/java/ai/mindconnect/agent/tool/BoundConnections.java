package ai.mindconnect.agent.tool;

import java.util.Map;
import java.util.NoSuchElementException;

/**
 * The accounts one call runs on, by the name of the parameter that chose
 * them: {@code bound.one()} for the usual single-ended tool,
 * {@code bound.get("from")} and {@code bound.get("to")} for one that moves
 * something between two.
 *
 * <p>Everything in here is already resolved and usable — a call that could
 * not be pointed at an account never reaches the tool.
 */
public record BoundConnections(Map<String, ToolConnection> byParam) {

    public BoundConnections {
        byParam = byParam == null ? Map.of() : Map.copyOf(byParam);
    }

    public static BoundConnections of(String param, ToolConnection connection) {
        return new BoundConnections(Map.of(param, connection));
    }

    /** The connection chosen for {@code param}. */
    public ToolConnection get(String param) {
        ToolConnection connection = byParam.get(param);
        if (connection == null) {
            throw new NoSuchElementException("No connection was bound for '" + param + "'");
        }
        return connection;
    }

    /**
     * The only connection, for the tool that has exactly one end — which is
     * nearly all of them.
     *
     * @throws IllegalStateException when the tool declared more than one; then
     *         the call has to say which it means
     */
    public ToolConnection one() {
        if (byParam.size() != 1) {
            throw new IllegalStateException("This tool has " + byParam.size()
                    + " connection parameters; ask for one by name");
        }
        return byParam.values().iterator().next();
    }

    public boolean isEmpty() {
        return byParam.isEmpty();
    }
}
