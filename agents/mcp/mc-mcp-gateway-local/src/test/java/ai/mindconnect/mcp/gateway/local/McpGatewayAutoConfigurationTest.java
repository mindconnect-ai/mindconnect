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
            assertThat(context.getBean(McpGateway.class)).isInstanceOf(LocalMcpGateway.class);
            assertThat(context).hasSingleBean(McpRegistryAdmin.class);
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
            assertThat(context).doesNotHaveBean(LocalMcpGateway.class);
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
