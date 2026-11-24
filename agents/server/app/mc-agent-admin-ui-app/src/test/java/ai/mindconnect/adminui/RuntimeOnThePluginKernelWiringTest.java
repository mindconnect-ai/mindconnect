package ai.mindconnect.adminui;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import ai.mindconnect.agent.runtime.api.port.in.SessionService;
import ai.mindconnect.agent.runtime.api.port.in.TurnService;
import ai.mindconnect.agent.runtime.api.port.out.AgentResolver;
import ai.mindconnect.agent.runtime.api.port.out.ToolResolver;
import ai.mindconnect.agent.runtime.llm.LlmCallTraceListener;
import ai.mindconnect.agent.sdk.AgentRuntime;

/**
 * The real app, started: the runtime on the plugin kernel stands beside the
 * earlier one, built from the application's own beans, with the
 * application's stores behind its ports.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "mindconnect.encryption.secret-key=test-key-32-characters-long-abcd",
        "mindconnect.auth.enabled=false"
})
class RuntimeOnThePluginKernelWiringTest {

    @DynamicPropertySource
    static void tempDataDir(DynamicPropertyRegistry registry) {
        try {
            var dir = Files.createTempDirectory("mc-runtime-wiring-test");
            registry.add("mindconnect.data.base-dir", dir::toString);
            registry.add("mindconnect.tools.base-dir", dir::toString);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired
    ApplicationContext context;

    @Test
    void its_services_are_beans_and_the_applications_stores_are_behind_its_ports() {
        assertThat(context.getBeansOfType(SessionService.class)).hasSize(1);
        assertThat(context.getBeansOfType(TurnService.class)).hasSize(1);
        AgentRuntime registry = context.getBean(AgentRuntime.class);
        // The application's agents and tools, next to what the capabilities contribute.
        assertThat(registry.all(AgentResolver.class)).isNotEmpty();
        assertThat(registry.all(ToolResolver.class)).hasSizeGreaterThan(1);
        // Every provider call is kept as a trace: the runtime's own listener, not nobody.
        assertThat(registry.service(LlmCallTraceListener.class)).isNotSameAs(LlmCallTraceListener.NONE);
    }
}
