package ai.mindconnect.extension.demo;

import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.agent.sdk.AgentRuntime;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * Joins the host through an auto-configuration, not component scanning —
 * the host scans its own packages only. Two beans: the plugin, which the
 * runtime starter installs while it builds the runtime (a Spring host
 * installs the plugins it finds as beans; a library host finds the same
 * class through {@code ServiceLoader}), and the controller, which needs the
 * built runtime and a web layer to answer on.
 */
@AutoConfiguration(beforeName = "ai.mindconnect.agent.starter.runtime.engine.RuntimeEngineAutoConfiguration")
public class DemoDungeonAutoConfiguration {

    @Bean
    DemoPlugin demoPlugin() {
        return new DemoPlugin();
    }

    @Bean
    @ConditionalOnWebApplication
    DungeonController dungeonController(AgentRuntime runtime, ScopeSupplier scope) {
        return new DungeonController(runtime, scope);
    }
}
