package ai.mindconnect.agent.tool;

import ai.mindconnect.agent.tool.AgentTool;

import java.util.Optional;
import java.util.Set;

/**
 * SPI for tool sources that contribute <em>multiple</em> tool names from a
 * single registration — complement to {@link ToolFactory} (which is strictly
 * 1:1 with a name).
 *
 * <p>Use cases:
 * <ul>
 *   <li>An MCP-server adapter that exposes a fixed bundle of sub-tools
 *       (e.g. {@code gmail_search_messages}, {@code gmail_read_message},
 *       {@code gmail_send_message}) sharing one underlying connection.</li>
 *   <li>Built-in tool bundles where several related tools share resources
 *       and want one place to manage them.</li>
 *   <li>Dynamic sources whose tool set changes at runtime — e.g. persisted
 *       workflows, where every workflow in the store is one tool.</li>
 * </ul>
 *
 * <p>Discovery: standard {@link java.util.ServiceLoader}, with
 * {@code META-INF/services/ai.mindconnect.agent.tool.MultiToolProvider}
 * listing one FQN per line, exactly like {@link ToolFactory}.
 *
 * <p>Lifecycle is symmetric to {@link ToolFactory}:
 * <ol>
 *   <li>The runtime instantiates the provider via its public no-arg constructor.</li>
 *   <li>{@link #bind(ToolEnvironment)} is invoked once; the provider captures
 *       only the dependencies it needs.</li>
 *   <li>{@link #isAvailable()} decides whether the provider participates at all
 *       (e.g. credentials missing → the whole bundle disappears).</li>
 *   <li>For each agent-configured tool the runtime calls
 *       {@link #create(String, AgentTool, ToolCallScope)} to build a fresh
 *       {@link Tool} instance.</li>
 * </ol>
 */
public interface MultiToolProvider {

    /**
     * Tool names this provider serves <em>right now</em>. The registry
     * consults this on every lookup ({@link ToolRegistry#knownToolNames()}
     * aggregation, the admin UI dropdown, and name→provider resolution), so
     * the returned set may change over the provider's lifetime — a provider
     * backed by mutable data should re-read its source here rather than
     * caching at {@link #bind}. Keep it cheap (an in-memory map or a
     * directory listing, not a network round-trip per call) and its order
     * stable.
     */
    Set<String> toolNames();

    /**
     * The provider's group — both the rubric under which its tools appear in
     * catalogs/pickers and the <em>machine namespace</em> its tool names live
     * in: by convention a provider's tool names compose as
     * {@code group() + "_" + localName} (group {@code "workflow"}, workflow
     * {@code "pipeline"} → tool {@code "workflow_pipeline"}; group
     * {@code "gmail"} → {@code "gmail_search_messages"}). Lowercase; UIs
     * capitalize for display. Default {@code "general"}.
     */
    default String group() { return "general"; }

    /** Optional capture of runtime dependencies; default is a no-op. */
    default void bind(ToolEnvironment env) {}

    /**
     * False disables the entire bundle — neither {@link #toolNames()} nor
     * {@link #create} will be honored by the registry. Default {@code true}.
     */
    default boolean isAvailable() { return true; }

    /**
     * Build a tool for the given name. Returns {@link Optional#empty()} if
     * the name is not served by this provider; the registry then continues
     * with the next provider.
     */
    Optional<Tool> create(String toolName, AgentTool agentTool, ToolCallScope scope);
}
