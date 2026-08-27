package ai.mindconnect.workflow.admin;

import ai.mindconnect.workflow.admin.ui.WorkflowAdminUiController;
import ai.mindconnect.workflow.persistence.file.FileWorkflowDataRepository;
import ai.mindconnect.workflow.persistence.port.WorkflowDataRepository;
import ai.mindconnect.workflow.persistence.file.FileWorkflowInstanceRepository;
import ai.mindconnect.workflow.persistence.port.WorkflowInstanceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.nio.file.Path;

/**
 * Auto-configures the embeddable workflow admin when present on a Spring Boot
 * host's classpath.
 *
 * <p>Registers a default {@link FileWorkflowDataRepository} (directory from
 * {@code mindconnect.workflow-admin.dir}, default {@code data/workflows}) and
 * the {@link WorkflowAdminUiController}. Host apps that want different storage
 * register their own {@link WorkflowDataRepository} bean and the
 * {@code @ConditionalOnMissingBean} gate steps aside.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
@Import({WorkflowAdminUiController.class,
        ai.mindconnect.workflow.admin.api.WorkflowApiController.class})
public class WorkflowAdminAutoConfiguration {

    /** The shared operations layer both the UI and the REST API delegate to. */
    @Bean
    @ConditionalOnMissingBean
    public ai.mindconnect.workflow.admin.service.WorkflowAdminService workflowAdminService(
            WorkflowDataRepository store, WorkflowInstanceRepository instances) {
        return new ai.mindconnect.workflow.admin.service.WorkflowAdminService(store, instances);
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowDataRepository workflowDataRepository(
            @Value("${mindconnect.workflow-admin.dir:data/workflows}") String dir) {
        return new FileWorkflowDataRepository(Path.of(dir));
    }

    /**
     * Where runs live — the instance is the run record: the resume state while
     * halted, the readable history after. Same directory as the definitions by
     * default; the repository keeps them in an {@code instances/} subdirectory,
     * so a run outlives the process that started it.
     */
    @Bean
    @ConditionalOnMissingBean
    public WorkflowInstanceRepository workflowInstanceRepository(
            @Value("${mindconnect.workflow-admin.dir:data/workflows}") String dir) {
        return new FileWorkflowInstanceRepository(Path.of(dir));
    }
}
