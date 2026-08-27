package ai.mindconnect.workflow.ui.diagram.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Standalone Spring Boot harness for the UiDiagram pipeline. Boots a web
 * server on port 8080, serves the static {@code index.html} from
 * {@code src/main/resources/static/}, and exposes
 * {@code /api/workflows/{name}/diagram} which returns the {@link
 * ai.mindconnect.ui.ext.diagram.UiDiagram} produced by
 * {@link ai.mindconnect.workflow.ui.diagram.WorkflowDiagramBuilder}.
 *
 * <p>Run with {@code mvn -f workflow/mc-workflow-ui-diagram-app/pom.xml spring-boot:run}.
 * Open {@code http://localhost:8080/} in a browser.
 *
 * <p>This module exists purely as a manual test harness — there is no
 * persistence, no security, and the sample workflows are hardcoded in
 * {@link WorkflowSamples}.
 */
@SpringBootApplication
public class DiagramApp {

    public static void main(String[] args) {
        SpringApplication.run(DiagramApp.class, args);
    }
}
