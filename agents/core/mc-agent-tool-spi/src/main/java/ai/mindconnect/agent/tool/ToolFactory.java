package ai.mindconnect.agent.tool;

import ai.mindconnect.agent.tool.AgentTool;

/**
 * SPI for contributing a single built-in tool to the agent runtime.
 *
 * <p>Each factory is responsible for one tool name. Implementations are
 * discovered via {@link java.util.ServiceLoader}. Each tool module ships a
 * file:
 * <pre>
 *   META-INF/services/ai.mindconnect.agent.tool.ToolFactory
 * </pre>
 * containing the fully-qualified class names of every factory it provides
 * (one per line).
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>The runtime instantiates each factory via its public no-arg constructor.</li>
 *   <li>{@link #bind(ToolEnvironment)} is called exactly once with the runtime
 *       environment so the factory can capture only the dependencies it needs.</li>
 *   <li>{@link #isAvailable()} is consulted to decide whether the factory can
 *       serve requests at all (e.g. a missing API key may disable a tool).</li>
 *   <li>{@link #create(AgentTool, ToolCallScope)} is invoked per tool resolution.</li>
 * </ol>
 */
public interface ToolFactory {

    /** Unique tool name this factory produces (e.g. {@code "web_search"}). */
    String name();

    /**
     * Rubric under which this tool is shown in catalogs and pickers
     * (e.g. {@code "web"}, {@code "documents"}, {@code "files"}).
     * Lowercase; UIs capitalize for display. Most built-in names already
     * follow the {@code group_name} convention (e.g. {@code web_search}).
     * Default {@code "general"}.
     */
    default String group() { return "general"; }

    /**
     * Called once after construction to wire the factory against the runtime
     * environment. Default implementation does nothing; override to grab
     * services or configuration from {@code env}.
     */
    default void bind(ToolEnvironment env) {}

    /**
     * Whether this factory has everything it needs to produce a working tool.
     * Defaults to {@code true}; override to disable the factory when required
     * configuration is missing.
     */
    default boolean isAvailable() { return true; }

    /**
     * JSON-Schema-shaped description of the config overrides this tool
     * understands in {@link ai.mindconnect.agent.tool.AgentTool#overrides()}
     * — operator-level settings the LLM never sees (e.g. {@code baseDir} for
     * file-rooted tools, {@code network} for code execution). Admin UIs render
     * this so overrides are discoverable instead of doc-only knowledge. The
     * reserved {@code params} override (parameter pinning) is a framework
     * feature and not listed here. Default: no overrides.
     */
    default java.util.Map<String, Object> overridesSchema() { return java.util.Map.of(); }

    /** Build a tool for the given configuration and call scope. */
    Tool create(AgentTool agentTool, ToolCallScope scope);
}
