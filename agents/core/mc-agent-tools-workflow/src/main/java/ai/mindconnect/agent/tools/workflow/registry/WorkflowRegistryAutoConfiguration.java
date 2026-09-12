package ai.mindconnect.agent.tools.workflow.registry;

import ai.mindconnect.workflow.persistence.port.WorkflowDataRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Contributes the workflow installer when this host has both a workflow store
 * to write into and the registry on the classpath. Either missing and there is
 * nothing to wire — the registry is optional, and so is the workflow engine.
 *
 * <p>Ordered after the auto-configurations that register a workflow store, so
 * that {@code @ConditionalOnBean} sees it.
 */
@AutoConfiguration(afterName = {
        "ai.mindconnect.workflow.admin.WorkflowAdminAutoConfiguration",
        "ai.mindconnect.workflow.persistence.pg.WorkflowPostgresAutoConfiguration"})
@ConditionalOnClass(name = "ai.mindconnect.agent.registry.port.out.RegistryInstaller")
public class WorkflowRegistryAutoConfiguration {

    @Bean
    @ConditionalOnBean(WorkflowDataRepository.class)
    @ConditionalOnMissingBean
    public WorkflowRegistryInstaller workflowRegistryInstaller(WorkflowDataRepository repository) {
        return new WorkflowRegistryInstaller(repository);
    }
}
