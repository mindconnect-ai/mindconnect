package ai.mindconnect.workflow.admin.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Standalone workflow admin. The embeddable {@code mc-workflow-admin-rest}
 * module auto-configures the controller and a file-based store; this app just
 * boots Spring Boot and serves the SPA shell at {@code /}.
 *
 * <p>SPA mode only (no SSR), no authentication — see {@code application.yaml}.
 */
@SpringBootApplication
public class WorkflowAdminApplication {
    public static void main(String[] args) {
        SpringApplication.run(WorkflowAdminApplication.class, args);
    }
}
