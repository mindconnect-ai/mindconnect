package ai.mindconnect.mcp.gateway.local;

import ai.mindconnect.mcp.gateway.McpCaller;
import ai.mindconnect.mcp.gateway.McpGateway;
import ai.mindconnect.mcp.gateway.McpProbeResult;
import ai.mindconnect.mcp.gateway.McpRegistryAdmin;
import ai.mindconnect.mcp.gateway.McpResult;
import ai.mindconnect.mcp.gateway.McpServerId;
import ai.mindconnect.mcp.gateway.McpServerInfo;
import ai.mindconnect.mcp.gateway.McpServerRegistration;
import ai.mindconnect.mcp.gateway.McpTarget;
import ai.mindconnect.mcp.gateway.McpTool;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What this module contributes is conditional, and a condition is only
 * provable by starting a context.
 */
class McpGatewayAutoConfigurationTest {

    @TempDir
    Path dataDir;

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(McpGatewayAutoConfiguration.class))
                // A bare runner converts with Spring's default service, which
                // does not know Duration; a real Boot application uses this
                // one. Without it the test would fail on
                // @Value("${…ttl:PT6H}") for a reason the product does not
                // have. It goes on the bean factory, because that is what
                // converts @Value on a bean method's parameters.
                .withInitializer(context -> context.getBeanFactory()
                        .setConversionService(ApplicationConversionService.getSharedInstance()))
                .withPropertyValues(
                        "mindconnect.data.base-dir=" + dataDir,
                        // Naming one keeps the probe from shelling out to
                        // podman and docker in a unit test.
                        "mindconnect.mcp.container-runtime=nothing-here");
    }

    @Test
    void by_default_the_local_gateway_and_its_admin_are_there() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(McpGateway.class);
            assertThat(context).hasSingleBean(NamespacedMcpGateways.class);
            assertThat(context).hasSingleBean(McpRegistryAdmin.class);
            // The gateway the application sees routes to one per namespace; the default namespace's is a local one.
            assertThat(context.getBean(NamespacedMcpGateways.class).forNamespace(ai.mindconnect.agent.Namespace.DEFAULT).gateway())
                    .isInstanceOf(LocalMcpGateway.class);
        });
    }

    @Test
    void by_default_registrations_are_files() {
        runner().run(context -> {
            NamespacedMcpGateways gateways = context.getBean(NamespacedMcpGateways.class);
            assertThat(gateways.forNamespace(ai.mindconnect.agent.Namespace.DEFAULT).repository())
                    .isInstanceOf(FileMcpServerRepository.class);
        });
    }

    @Test
    void a_store_factory_bean_takes_the_place_of_the_files() {
        // How the Postgres starter puts registrations and discovery cache into tables.
        runner().withUserConfiguration(OwnStores.class).run(context -> {
            assertThat(context).hasNotFailed();
            NamespacedMcpGateways gateways = context.getBean(NamespacedMcpGateways.class);
            assertThat(gateways.forNamespace(new ai.mindconnect.agent.Namespace("acme")).repository())
                    .isSameAs(OwnStores.REPOSITORY);
            // The bundled registrations went into that store, not into a directory.
            assertThat(dataDir.resolve("local")).doesNotExist();
        });
    }

    @Test
    void postgres_without_a_store_factory_keeps_the_files() {
        // mc-mcp-gateway-pg not on the classpath: the servers stay where they are rather than vanish.
        runner().withPropertyValues("mindconnect.persistence=postgres").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(NamespacedMcpGateways.class)
                    .forNamespace(ai.mindconnect.agent.Namespace.DEFAULT).repository())
                    .isInstanceOf(FileMcpServerRepository.class);
        });
    }

    @Test
    void a_host_with_its_own_gateway_gets_a_context_that_starts() {
        // The point of this test: it did not. The admin bean returned null,
        // Spring filed a NullBean whose declared type still satisfied the
        // screen's @ConditionalOnBean, and the screen's required constructor
        // argument could then not be resolved — so replacing the gateway,
        // which this module exists to allow, brought the application down at
        // startup instead of quietly doing without the admin screen.
        runner().withUserConfiguration(OwnGateway.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(McpGateway.class);
            assertThat(context.getBean(McpGateway.class)).isInstanceOf(StubGateway.class);
            assertThat(context).doesNotHaveBean(NamespacedMcpGateways.class);
            // No admin bean at all — not a bean that is null. The screen's
            // condition then fails and the screen stays away.
            assertThat(context).doesNotHaveBean(McpRegistryAdmin.class);
        });
    }

    @Test
    void switched_off_the_module_contributes_nothing() {
        runner().withPropertyValues("mindconnect.mcp.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(McpGateway.class);
            assertThat(context).doesNotHaveBean(McpRegistryAdmin.class);
            assertThat(context).doesNotHaveBean(McpServerRepository.class);
        });
    }

    @Test
    void the_suggestion_catalog_stays_off_unless_asked_for() {
        // It fetches from a third party; an installation that wants no
        // outbound call should not make one because a screen was opened.
        runner().run(context ->
                assertThat(context).doesNotHaveBean(ai.mindconnect.mcp.gateway.McpCatalog.class));
        runner().withPropertyValues("mindconnect.mcp.catalog.enabled=true").run(context ->
                assertThat(context).hasSingleBean(ai.mindconnect.mcp.gateway.McpCatalog.class));
    }

    @Test
    void with_sign_in_on_a_process_target_does_not_start() {
        // /mcp-gateway asks for a login, not an admin role: without this,
        // anybody who can sign in runs a command on the server with a probe.
        runner().withPropertyValues("mindconnect.auth.enabled=true").run(context -> {
            McpProbeResult probe = context.getBean(McpRegistryAdmin.class).probe(new McpServerRegistration(
                    McpServerId.of("shell"), null, null, true, "shell",
                    new McpTarget.Process(List.of("/bin/sh", "-c", "env"), Map.of()), null));

            assertThat(probe.ok()).isFalse();
            assertThat(probe.message()).contains("process targets are switched off");
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class OwnStores {
        static final McpServerRepository REPOSITORY = new InMemoryRepository();

        @Bean
        McpStoreFactory mcpStoreFactory() {
            return new McpStoreFactory() {
                @Override
                public McpServerRepository serverRepository(ai.mindconnect.agent.Namespace namespace) {
                    return REPOSITORY;
                }

                @Override
                public McpDiscoveryStore discoveryStore(ai.mindconnect.agent.Namespace namespace) {
                    return new McpDiscoveryStore() {
                        @Override public java.util.Optional<ai.mindconnect.mcp.gateway.McpDiscovery> find(
                                McpServerId server) { return java.util.Optional.empty(); }
                        @Override public void save(McpServerId server,
                                                   ai.mindconnect.mcp.gateway.McpDiscovery discovery) { }
                        @Override public boolean delete(McpServerId server) { return false; }
                    };
                }
            };
        }
    }

    /** A registration store that is neither a file nor a table. */
    static final class InMemoryRepository implements McpServerRepository {
        private final Map<McpServerId, McpServerRegistration> byId = new java.util.concurrent.ConcurrentHashMap<>();
        @Override public java.util.Optional<McpServerRegistration> findById(McpServerId id) {
            return java.util.Optional.ofNullable(byId.get(id));
        }
        @Override public List<McpServerRegistration> findAll() { return List.copyOf(byId.values()); }
        @Override public java.util.Optional<McpServerRegistration> findByName(String name) {
            return byId.values().stream().filter(r -> r.displayName().equalsIgnoreCase(name)).findFirst();
        }
        @Override public void save(McpServerRegistration registration) { byId.put(registration.id(), registration); }
        @Override public void deleteById(McpServerId id) { byId.remove(id); }
        @Override public long version() { return byId.hashCode(); }
    }

    @Configuration(proxyBeanMethods = false)
    static class OwnGateway {
        @Bean
        McpGateway mcpGateway() {
            return new StubGateway();
        }
    }

    /** Stands in for the gateway client against a remote gateway server. */
    static final class StubGateway implements McpGateway {
        @Override public List<McpServerInfo> servers() { return List.of(); }
        @Override public List<McpTool> tools(McpServerId server) { return List.of(); }
        @Override public McpResult call(McpCaller caller, McpServerId server, String toolName,
                                        Map<String, Object> arguments) {
            throw new UnsupportedOperationException();
        }
        @Override public void release(McpCaller caller) { }
        @Override public long catalogVersion() { return 0L; }
    }
}
