package ai.mindconnect.mcp.gateway.local;

import ai.mindconnect.agent.NamespacePurge;
import ai.mindconnect.agent.NamespaceRouted;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.mcp.gateway.McpCatalog;
import ai.mindconnect.mcp.gateway.McpGateway;
import ai.mindconnect.mcp.gateway.McpRegistryAdmin;
import ai.mindconnect.mcp.proxy.McpProxy;
import ai.mindconnect.mcp.proxy.McpSessionRegistry;
import ai.mindconnect.mcp.proxy.SdkMcpProxy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Wires the in-process gateway when this module is on the classpath. A host
 * that wants a different implementation — a client against a gateway server,
 * a stub in a test — defines its own {@link McpGateway} bean and this one
 * backs off.
 *
 * <p>Nothing else has to change for the tools to appear: the tool provider
 * asks its {@code ToolEnvironment} for an {@code McpGateway}, and the
 * runtime's environment falls back to the host's beans by type.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "mindconnect.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class McpGatewayAutoConfiguration {

    /**
     * The data directory. Both MCP stores live in the configuration folder of
     * the namespace this process serves — {@code <namespace>/system/mcp-servers}
     * and {@code <namespace>/system/mcp-schema-cache}, beside agents and LLM
     * configs.
     */
    static Path storageRoot(String dataBaseDir) {
        return Path.of(dataBaseDir);
    }

    /**
     * The gateways, one per namespace. The default namespace is seeded with the
     * bundled registrations here, before anything is served — the first tool
     * lookup happens while the tool registry is being built. Existing files are
     * never overwritten; a namespace somebody creates later starts empty.
     * Declared as the holder, not as a gateway: {@code @ConditionalOnBean} below
     * asks for this type, and a host with its own {@link McpGateway} takes it out.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(McpGateway.class)
    public NamespacedMcpGateways mcpGateways(
            McpProxy mcpProxy,
            McpSessionRegistry sessions,
            Environment environment,
            ObjectProvider<ScopeSupplier> scope,
            // The installation's default namespace: seeds go there, whatever scope a thread binds — and at
            // start-up the main thread binds none.
            @Value("${mindconnect.namespace:local}") String defaultNamespace,
            @Value("${mindconnect.data.base-dir:./data}") String dataBaseDir,
            @Value("${mindconnect.mcp.container-runtime:auto}") String containerRuntime) {
        // Whether process and docker targets start is the installation's call:
        // mindconnect.mcp.allow-process / allow-docker, unset following sign-in.
        NamespacedMcpGateways gateways = new NamespacedMcpGateways(storageRoot(dataBaseDir), mcpProxy, sessions,
                containerRuntime, McpStartPolicy.from(environment));
        gateways.seed(seedNamespace(scope, defaultNamespace), "classpath:initial-data/mcp-servers/*.json");
        return gateways;
    }

    /** A deleted namespace's gateway is closed and forgotten; its registrations went with its directory. */
    @Bean
    @ConditionalOnBean(NamespacedMcpGateways.class)
    public NamespacePurge mcpGatewayPurge(NamespacedMcpGateways gateways) {
        return gateways::drop;
    }

    /** The registrations of the namespace a call works in. */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(NamespacedMcpGateways.class)
    public McpServerRepository mcpServerRepository(NamespacedMcpGateways gateways, ObjectProvider<ScopeSupplier> scope) {
        return NamespaceRouted.route(McpServerRepository.class, scope.getIfAvailable(ScopeSupplier::local),
                ns -> gateways.forNamespace(ns).repository());
    }

    /**
     * The catalog to suggest servers from. Off unless enabled: it fetches
     * from the internet, and an installation that does not want outbound
     * calls should not make one because a screen was opened.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "mindconnect.mcp.catalog", name = "enabled", havingValue = "true")
    public McpCatalog mcpCatalog(
            @Value("${mindconnect.mcp.catalog.url:}") String url,
            @Value("${mindconnect.mcp.catalog.ttl:PT6H}") Duration ttl,
            @Value("${mindconnect.mcp.catalog.timeout:PT10S}") Duration timeout) {
        return new DockerMcpCatalog(url, ttl, timeout);
    }

    @Bean
    @ConditionalOnMissingBean
    public McpProxy mcpProxy() {
        return new SdkMcpProxy();
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean
    public McpSessionRegistry mcpSessionRegistry(McpProxy mcpProxy) {
        return new McpSessionRegistry(mcpProxy);
    }

    /** The gateway of the namespace a call works in. */
    @Bean
    @ConditionalOnMissingBean(McpGateway.class)
    @ConditionalOnBean(NamespacedMcpGateways.class)
    public McpGateway mcpGateway(NamespacedMcpGateways gateways, ObjectProvider<ScopeSupplier> scope) {
        return NamespaceRouted.route(McpGateway.class, scope.getIfAvailable(ScopeSupplier::local),
                ns -> gateways.forNamespace(ns).gateway());
    }

    /**
     * Administering the in-process gateway — and only that one. A host that
     * replaced the gateway administers it itself, so here there is simply no
     * bean: the screen's own condition then fails and the screen stays away,
     * which is what was always intended.
     *
     * <p>It used to be intended by returning {@code null}. That does not
     * produce "no bean" — Spring files a {@code NullBean} whose declared type
     * still satisfies the screen's {@code @ConditionalOnBean}, so the
     * controller was built and its required constructor argument could not be
     * resolved. The application failed to start instead of quietly doing
     * without the screen.
     *
     * <p><b>Must stay below {@link #mcpGateway}.</b> Within one configuration
     * class, {@code @ConditionalOnBean} only sees the bean methods declared
     * before it.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(NamespacedMcpGateways.class)
    public McpRegistryAdmin mcpRegistryAdmin(NamespacedMcpGateways gateways, ObjectProvider<ScopeSupplier> scope) {
        return NamespaceRouted.route(McpRegistryAdmin.class, scope.getIfAvailable(ScopeSupplier::local),
                ns -> gateways.forNamespace(ns).admin());
    }
    /**
     * Where bundled data is seeded: the namespace a fixed scope names — a
     * single-namespace host chose it — and otherwise the installation default,
     * because a thread-bound scope binds nothing at start-up.
     */
    private static ai.mindconnect.agent.Namespace seedNamespace(
            ObjectProvider<ScopeSupplier> scope, String defaultNamespace) {
        ScopeSupplier supplier = scope.getIfAvailable();
        return supplier == null || supplier instanceof ai.mindconnect.agent.ThreadBoundScope
                ? new ai.mindconnect.agent.Namespace(defaultNamespace)
                : supplier.namespace();
    }
}
