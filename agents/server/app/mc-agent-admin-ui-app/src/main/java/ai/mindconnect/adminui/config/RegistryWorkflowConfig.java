package ai.mindconnect.adminui.config;

import ai.mindconnect.agent.registry.workflow.WorkflowRegistryInstaller;
import ai.mindconnect.workflow.jackson.JacksonWorkflowSerializer;
import ai.mindconnect.workflow.jackson.WorkflowObjectMapperFactory;
import ai.mindconnect.workflow.persistence.port.WorkflowDataRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The registry's workflow installer, wired here because this app is the host that
 * has both a registry and a workflow store; the installer module knows no Spring.
 */
@Configuration
public class RegistryWorkflowConfig {

    @Bean
    WorkflowRegistryInstaller workflowRegistryInstaller(WorkflowDataRepository repository) {
        return new WorkflowRegistryInstaller(repository,
                new JacksonWorkflowSerializer(WorkflowObjectMapperFactory.create()));
    }
}
