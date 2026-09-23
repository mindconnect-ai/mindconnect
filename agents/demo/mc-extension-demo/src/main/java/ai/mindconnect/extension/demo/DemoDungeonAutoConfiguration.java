package ai.mindconnect.extension.demo;

import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.builder.AgentRuntime;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * Joins the host through an auto-configuration, not component scanning —
 * the host scans its own packages only. Two beans: the runtime feature,
 * which the runtime starter collects while it builds the runtime (a Spring
 * host installs features it finds as beans; a library host finds the same
 * class through {@code ServiceLoader}), and the controller, which needs the
 * built runtime and a web layer to answer on.
 */
@AutoConfiguration(beforeName = "ai.mindconnect.agent.starter.runtime.AgentRuntimeAutoConfiguration")
public class DemoDungeonAutoConfiguration {

    @Bean
    DemoFeature demoFeature() {
        return new DemoFeature();
    }

    @Bean
    @ConditionalOnWebApplication
    DungeonController dungeonController(AgentRuntime runtime, ScopeSupplier scope) {
        return new DungeonController(runtime, scope);
    }
}
