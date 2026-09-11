package ai.mindconnect.agent.tool;

import java.util.Map;

/**
 * The operator's say over the tools: which are off, and how they describe
 * themselves.
 *
 * <p>Deliberately <em>not</em> a list of tools. What exists comes from the
 * classpath and from registrations; this only records what should differ
 * (concept 22 §2). An empty repository is the shipped state, and deleting it
 * restores that state.
 *
 * <p>Like every store, an implementation is bound to the one namespace its
 * process serves; nothing here names it.
 *
 * <p>The key is the tool name as the registry knows it. For a tool that
 * comes from a registered MCP server that name contains the server's prefix,
 * which is why the prefix is fixed once a server is registered.
 */
public interface ToolRepository {

    /** What was decided for this tool, or {@link ToolSettings#none()}. */
    ToolSettings settings(String toolName);

    /** Everything that was decided, by tool name. Only tools that deviate appear. */
    Map<String, ToolSettings> all();

    /** Records a decision; an empty {@link ToolSettings} removes it instead. */
    void save(String toolName, ToolSettings settings);

    /** Back to "as the source defines it". */
    void delete(String toolName);

    /**
     * Changes with every modification. Callers on the hot path hold their own
     * view and compare this rather than re-reading.
     */
    long version();
}
