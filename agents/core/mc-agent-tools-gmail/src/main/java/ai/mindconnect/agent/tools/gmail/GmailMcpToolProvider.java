package ai.mindconnect.agent.tools.gmail;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.MultiToolProvider;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.mcp.proxy.DockerSpawnBuilder;
import ai.mindconnect.mcp.proxy.McpSessionRegistry;
import ai.mindconnect.mcp.proxy.McpStdioSpawn;
import ai.mindconnect.mcp.proxy.McpTool;
import ai.mindconnect.mcp.proxy.SdkMcpProxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * MCP provider for the GongRzhe/Gmail-MCP-Server. Discovers the server's
 * sub-tools lazily and exposes them as agent tools — names, descriptions
 * and schemas come from the server itself, not from hand-written code.
 *
 * <p>Mapping convention: agent-tool name is {@code "gmail_" + subToolName}.
 * The server exposes {@code search_emails}, {@code read_email}, … which become
 * {@code gmail_search_emails}, {@code gmail_read_email} from the LLM's POV.
 *
 * <p>Schema discovery runs once per process, persisted to
 * {@code <dataBaseDir>/system/mcp-schema-cache/gmail.json}. Delete the file
 * to force a re-discovery (e.g. after upgrading the MCP image to a version
 * with new tools).
 */
public final class GmailMcpToolProvider implements MultiToolProvider {

    private static final Logger log = LoggerFactory.getLogger(GmailMcpToolProvider.class);

    static final String PROVIDER_KEY = "gmail";

    /** Docker image holding the Gmail MCP server. Override via system property. */
    static final String IMAGE = System.getProperty(
            "mc.tools.gmail.docker-image", "mcp/gmail:latest");

    /** Host directory with gcp-oauth.keys.json + credentials.json. */
    static final String HOST_CRED_DIR = System.getProperty(
            "mc.tools.gmail.credentials-dir",
            System.getProperty("user.home") + "/.gmail-mcp");

    /**
     * Inside the container the server resolves both files under
     * {@code /root/.gmail-mcp/}. The 1.0.0 image needs explicit env-var
     * overrides because its default-path computation captures HOME at build
     * time, not runtime.
     */
    static final String CONTAINER_CRED_DIR = "/root/.gmail-mcp";

    /** All agent-facing tool names start with this. */
    static final String TOOL_NAME_PREFIX = "gmail_";

    private final SdkMcpProxy proxy = new SdkMcpProxy();
    private final McpSessionRegistry registry = new McpSessionRegistry(proxy);

    /** agent-tool name → server-side sub-tool descriptor */
    private Map<String, McpTool> toolsByAgentName = Map.of();
    private boolean available;

    @Override
    public Set<String> toolNames() { return toolsByAgentName.keySet(); }

    @Override
    public String group() { return "gmail"; }

    @Override
    public boolean isAvailable() { return available; }

    @Override
    public void bind(ToolEnvironment env) {
        File oauthKeys = new File(HOST_CRED_DIR, "gcp-oauth.keys.json");
        File credsFile = new File(HOST_CRED_DIR, "credentials.json");
        if (!oauthKeys.isFile() || !credsFile.isFile()) {
            log.warn("gmail tools unavailable: missing files in '{}' (oauth-keys={}, credentials={}). " +
                    "Place gcp-oauth.keys.json from Google Cloud Console there, then run " +
                    "`npx @gongrzhe/server-gmail-autoauth-mcp auth`.",
                    HOST_CRED_DIR, oauthKeys.isFile(), credsFile.isFile());
            this.available = false;
            return;
        }

        String dataBaseDir = env.getString("dataBaseDir").orElse("data");
        Path cacheFile = Paths.get(dataBaseDir, "system", "mcp-schema-cache", "gmail.json");
        McpSchemaCache cache = new McpSchemaCache(cacheFile);

        try {
            var discovered = cache.loadOrFetch(proxy, spawn());
            Map<String, McpTool> map = new LinkedHashMap<>();
            for (McpTool t : discovered) {
                map.put(TOOL_NAME_PREFIX + t.name(), t);
            }
            this.toolsByAgentName = Map.copyOf(map);
            this.available = !map.isEmpty();
            log.info("gmail provider: discovered {} tool(s): {}",
                    toolsByAgentName.size(), toolsByAgentName.keySet());
        } catch (Exception e) {
            log.error("gmail provider: failed to discover tools — provider will be unavailable", e);
            this.available = false;
            return;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(registry::shutdown,
                "gmail-mcp-registry-shutdown"));
    }

    @Override
    public Optional<Tool> create(String toolName, AgentTool agentTool, ToolCallScope scope) {
        McpTool sub = toolsByAgentName.get(toolName);
        if (sub == null) return Optional.empty();
        return Optional.of(new McpToolAdapter(
                toolName,
                sub.name(),
                sub.description(),
                sub.inputSchema(),
                registry,
                scope.sessionId(),
                PROVIDER_KEY,
                GmailMcpToolProvider::spawn));
    }

    /** Spawn descriptor — same for every call. */
    static McpStdioSpawn spawn() {
        return DockerSpawnBuilder.of(IMAGE)
                .mount(HOST_CRED_DIR, CONTAINER_CRED_DIR)
                .env("GMAIL_OAUTH_PATH", CONTAINER_CRED_DIR + "/gcp-oauth.keys.json")
                .env("GMAIL_CREDENTIALS_PATH", CONTAINER_CRED_DIR + "/credentials.json")
                .build();
    }
}
