package ai.mindconnect.agent.tools.mcp;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.MultiToolProvider;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.mcp.gateway.McpGateway;
import ai.mindconnect.mcp.gateway.McpServerId;
import ai.mindconnect.mcp.gateway.McpServerInfo;
import ai.mindconnect.mcp.gateway.McpTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Exposes every registered MCP server's tools as agent tools. One provider
 * for all servers — where a server comes from, and whether the gateway
 * behind it runs here or elsewhere, is not this class's business.
 *
 * <p>Names compose as {@code <toolNamePrefix>_<subTool>}: a server
 * registered with prefix {@code gmail} turns its {@code search_emails} into
 * {@code gmail_search_emails}.
 *
 * <p>{@link #toolNames()} is asked on every single tool lookup, so it must
 * not rediscover anything; it compares {@link McpGateway#catalogVersion()}
 * and rebuilds only when that moved. A server registered while the
 * application runs therefore shows up on the next lookup, without a restart
 * and without a container start per keystroke.
 */
public final class McpMultiToolProvider implements MultiToolProvider {

    private static final Logger log = LoggerFactory.getLogger(McpMultiToolProvider.class);

    /** Group of every MCP-backed tool; the server shows as the tool's subgroup. */
    static final String GROUP = "mcp";

    private volatile McpGateway gateway;

    /** One agent tool name resolved to its server and sub-tool. */
    private record Binding(McpServerId serverId, String serverName, McpTool tool) {
    }

    /** The bindings as of one catalog version, swapped as a whole. */
    private record Catalog(long version, Map<String, Binding> bindings) {
    }

    private volatile Catalog catalog;

    @Override
    public Set<String> toolNames() {
        return bindings().keySet();
    }

    @Override
    public String group() {
        return GROUP;
    }

    /**
     * The server a tool came from. All MCP tools share one group, so the
     * server is what tells them apart in a catalog — with a handful of
     * servers, the difference between a readable list and forty names in a row.
     */
    @Override
    public String subgroup(String toolName) {
        Binding binding = bindings().get(toolName);
        return binding == null ? null : binding.serverName();
    }

    @Override
    public boolean isAvailable() {
        return gateway != null;
    }

    @Override
    public void bind(ToolEnvironment env) {
        this.gateway = env.get(McpGateway.class).orElse(null);
        if (gateway == null) {
            log.debug("no McpGateway available — MCP tools stay out of the catalog");
        }
    }

    @Override
    public Optional<Tool> create(String toolName, AgentTool agentTool, ToolCallScope scope) {
        Binding binding = bindings().get(toolName);
        McpGateway current = gateway;
        if (binding == null || current == null) {
            return Optional.empty();
        }
        return Optional.of(new McpToolAdapter(
                current,
                scope,
                binding.serverId(),
                toolName,
                binding.tool().name(),
                binding.tool().description(),
                binding.tool().inputSchema()));
    }

    /**
     * The name→server map, rebuilt when the gateway's catalog moved.
     * Synchronized so a change is applied once rather than by every thread
     * that happens to look; the version check in front keeps the common case
     * to a field read and a comparison.
     */
    private Map<String, Binding> bindings() {
        McpGateway current = gateway;
        if (current == null) {
            return Map.of();
        }
        long version = current.catalogVersion();
        Catalog seen = catalog;
        if (seen != null && seen.version() == version) {
            return seen.bindings();
        }
        synchronized (this) {
            seen = catalog;
            if (seen != null && seen.version() == version) {
                return seen.bindings();
            }
            Map<String, Binding> discovered = new LinkedHashMap<>();
            for (McpServerInfo server : current.servers()) {
                for (McpTool tool : current.tools(server.id())) {
                    String name = server.toolNamePrefix() + "_" + tool.name();
                    Binding clash = discovered.putIfAbsent(name,
                            new Binding(server.id(), server.displayName(), tool));
                    if (clash != null) {
                        log.warn("MCP tool name '{}' claimed by servers '{}' and '{}' — keeping the first",
                                name, clash.serverId(), server.id());
                    }
                }
            }
            // Not Map.copyOf: that returns an unordered map, and the SPI asks
            // toolNames() for a stable order — catalogs and pickers render it.
            Map<String, Binding> bindings = Collections.unmodifiableMap(discovered);
            catalog = new Catalog(version, bindings);
            log.info("MCP tools available: {}", discovered.keySet());
            return bindings;
        }
    }
}
