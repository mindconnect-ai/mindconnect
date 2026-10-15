package ai.mindconnect.mcp.gateway.local;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.mcp.gateway.McpCatalog;
import ai.mindconnect.mcp.gateway.McpGateway;
import ai.mindconnect.mcp.gateway.McpRegistryAdmin;
import ai.mindconnect.mcp.proxy.McpProxy;
import ai.mindconnect.mcp.proxy.McpSessionRegistry;
import ai.mindconnect.mcp.proxy.SdkMcpProxy;
import ai.mindconnect.namespace.port.out.NamespacePurge;
import ai.mindconnect.namespace.service.StartupScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(McpGatewayAutoConfiguration.class);

    /**
     * The data directory. On files both MCP stores live in the configuration
     * folder of the namespace this process serves — {@code <namespace>/system/mcp-servers}
     * and {@code <namespace>/system/mcp-schema-cache}, beside agents and LLM
     * configs. Under {@code mindconnect.persistence=postgres} the Postgres
     * starter contributes a {@link McpStoreFactory} of tables instead.
     */
    static Path storageRoot(String dataBaseDir) {
        return Path.of(dataBaseDir);
    }

    /**
     * The registrations, in the namespace of the call. The default namespace is
     * seeded with the bundled registrations here, before anything is served —
     * the first tool lookup happens while the tool registry is being built.
     * Existing files are never overwritten; a namespace somebody creates later
     * starts empty. A host with its own {@link McpGateway} takes this out too.
     */
    @Bean
    @ConditionalOnMissingBean({McpServerRepository.class, McpGateway.class})
    public McpServerRepository mcpServerRepository(
            Environment environment,
            ObjectProvider<ScopeSupplier> scope,
            ObjectProvider<McpStoreFactory> stores,
            // The installation's default namespace: start-up works there when nothing else is bound.
            @Value("${mindconnect.namespace:local}") String defaultNamespace,
            @Value("${mindconnect.data.base-dir:./data}") String dataBaseDir) {
        ScopeSupplier current = scope.getIfAvailable(ScopeSupplier::local);
        McpServerRepository repository = stores.getIfAvailable(() -> fileStores(environment, dataBaseDir))
                .serverRepository(current);
        StartupScope.run(current, new Namespace(defaultNamespace),
                () -> BundledMcpRegistrations.seed(repository, "classpath:initial-data/mcp-servers/*.json"));
        return repository;
    }

    /**
     * Registrations and discovery cache as files — unless a {@link McpStoreFactory}
     * bean says otherwise. Postgres persistence without one (its adapter not on
     * the classpath) keeps the files, and says so.
     */
    private static McpStoreFactory fileStores(Environment environment, String dataBaseDir) {
        if ("postgres".equals(environment.getProperty("mindconnect.persistence"))) {
            log.warn("mindconnect.persistence=postgres, but no Postgres store for MCP registrations "
                    + "(mc-mcp-gateway-pg) on the classpath — they stay in files under {}", dataBaseDir);
        }
        return McpStoreFactory.files(storageRoot(dataBaseDir));
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

    /**
     * The gateway, one for every namespace: what it discovers and the
     * connections it opens are those of the namespace of the call.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(McpGateway.class)
    @ConditionalOnBean(McpServerRepository.class)
    public LocalMcpGateway mcpGateway(
            McpServerRepository repository,
            McpProxy mcpProxy,
            McpSessionRegistry sessions,
            Environment environment,
            ObjectProvider<ScopeSupplier> scope,
            ObjectProvider<McpStoreFactory> stores,
            @Value("${mindconnect.data.base-dir:./data}") String dataBaseDir,
            @Value("${mindconnect.mcp.container-runtime:auto}") String containerRuntime) {
        ScopeSupplier current = scope.getIfAvailable(ScopeSupplier::local);
        McpDiscoveryStore discoveries = stores.getIfAvailable(() -> McpStoreFactory.files(storageRoot(dataBaseDir)))
                .discoveryStore(current);
        // Whether process and docker targets start is the installation's call:
        // mindconnect.mcp.allow-process / allow-docker, unset following sign-in.
        return new LocalMcpGateway(repository, mcpProxy, sessions, discoveries, current,
                containerRuntime, McpStartPolicy.from(environment));
    }

    /** A deleted namespace is forgotten by the gateway; its registrations went with its directory or its rows. */
    @Bean
    @ConditionalOnBean(LocalMcpGateway.class)
    public NamespacePurge mcpGatewayPurge(LocalMcpGateway gateway) {
        return gateway::forget;
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
    @ConditionalOnBean(LocalMcpGateway.class)
    public McpRegistryAdmin mcpRegistryAdmin(McpServerRepository repository, LocalMcpGateway gateway) {
        return new LocalMcpRegistryAdmin(repository, gateway);
    }
}
