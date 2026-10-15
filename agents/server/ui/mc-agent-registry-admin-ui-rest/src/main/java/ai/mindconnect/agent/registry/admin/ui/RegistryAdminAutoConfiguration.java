package ai.mindconnect.agent.registry.admin.ui;

import ai.mindconnect.agent.registry.service.RegistryService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Adds the registry screen to a host application that has a registry service.
 * Without one there is nothing to browse, and the screen stays away rather
 * than rendering an empty shell.
 *
 * <p>The ordering hint names the adapter module's configuration by string:
 * {@code @ConditionalOnBean} only sees what has been registered by the time it
 * runs, and this module must not depend on any particular implementation to
 * say so.
 */
@AutoConfiguration
@AutoConfigureAfter(name = "ai.mindconnect.agent.registry.spring.RegistryAutoConfiguration")
public class RegistryAdminAutoConfiguration {

    @Bean
    @ConditionalOnBean(RegistryService.class)
    @ConditionalOnMissingBean
    public RegistryUiController registryUiController(RegistryService registry) {
        return new RegistryUiController(registry);
    }
}
