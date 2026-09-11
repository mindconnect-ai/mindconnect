package ai.mindconnect.mcp.gateway.local;

import ai.mindconnect.initialdata.FileCopyInitialDataInstaller;
import ai.mindconnect.agent.Namespace;
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
     * Seeds bundled registrations before serving any, so the first tool
     * lookup — which happens while the tool registry is being built, long
     * before any {@code ApplicationRunner} — already sees them. Existing
     * files are never overwritten.
     */
    @Bean
    @ConditionalOnMissingBean
    public McpServerRepository mcpServerRepository(
            @Value("${mindconnect.data.base-dir:./data}") String dataBaseDir,
            ObjectProvider<Namespace> namespace) {
        // Bound to the namespace this process serves, like every store, and
        // seeded before anything is served: the first tool lookup happens
        // while the tool registry is being built.
        FileMcpServerRepository repository = new FileMcpServerRepository(
                storageRoot(dataBaseDir), namespace.getIfAvailable(() -> Namespace.DEFAULT));
        new FileCopyInitialDataInstaller(repository.directory())
                .install("classpath:initial-data/mcp-servers/*.json");
        return repository;
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
     * Declared as {@link LocalMcpGateway}, not as {@link McpGateway}: a
     * {@code @ConditionalOnBean} sees a bean's <em>declared</em> type, never
     * what a factory method happens to return, and {@link #mcpRegistryAdmin}
     * below has to be able to ask whether this one exists.
     *
     * <p>The back-off is stated explicitly for the same reason — a host that
     * brings its own {@code McpGateway} must take this one out of the
     * running, and an unqualified {@code @ConditionalOnMissingBean} would
     * only look for a {@code LocalMcpGateway} and leave two gateways behind.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(McpGateway.class)
    public LocalMcpGateway mcpGateway(
            McpServerRepository repository,
            McpProxy mcpProxy,
            McpSessionRegistry sessions,
            ObjectProvider<Namespace> namespace,
            Environment environment,
            @Value("${mindconnect.data.base-dir:./data}") String dataBaseDir,
            @Value("${mindconnect.mcp.container-runtime:auto}") String containerRuntime) {
        // Whether process and docker targets start is the installation's call:
        // mindconnect.mcp.allow-process / allow-docker, unset following sign-in.
        return new LocalMcpGateway(repository, mcpProxy, sessions,
                storageRoot(dataBaseDir), namespace.getIfAvailable(() -> Namespace.DEFAULT),
                containerRuntime, McpStartPolicy.from(environment));
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
