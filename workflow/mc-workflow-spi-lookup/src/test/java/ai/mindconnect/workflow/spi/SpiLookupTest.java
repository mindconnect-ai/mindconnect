package ai.mindconnect.workflow.spi;

import ai.mindconnect.workflow.domain.*;
import ai.mindconnect.workflow.execution.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link SpiWorkflowContextFactory} discovers and applies all
 * {@link WorkflowConfigurer} implementations on the test classpath.
 *
 * <p>The test module depends on: mc-workflow-code (via javascript/groovy),
 * javascript, groovy, http, spel — so those configurers must be discovered.
 */
public class SpiLookupTest {

    // -----------------------------------------------------------------------
    // Discovery
    // -----------------------------------------------------------------------

    @Test
    public void discoversAllConfigurersPresentOnClasspath() {
        List<WorkflowConfigurer> configurers = SpiWorkflowContextFactory.discoverConfigurers();

        List<String> names = configurers.stream()
                .map(c -> c.getClass().getSimpleName())
                .toList();

        assertThat(names).containsExactlyInAnyOrder(
                "JavaScriptWorkflowConfigurer",
                "GroovyWorkflowConfigurer"
        );
    }



    // -----------------------------------------------------------------------
    // End-to-end execution via SPI-configured factory
    // -----------------------------------------------------------------------

    @Test
    public void javaScriptStepExecutesViaDiscoveredFactory() {
        WorkflowExecutorService service = new WorkflowExecutorService(
                SpiWorkflowContextFactory.create());

        CodeData step = new CodeData();
        step.setName("js");
        step.setLanguage("javascript");
        step.setCode("a + b");
        step.setAssignResultToVar("result");

        WorkflowData wf = new WorkflowData();
        wf.setName("wf");
        wf.addSteps(step);
        wf.setResultFrom("result");

        WorkflowResult result = service.executeWorkflow(wf, Map.of("a", 3, "b", 4));
        assertThat(result.isSuccess()).isTrue();
        assertThat(((Number) result.getResult()).intValue()).isEqualTo(7);
    }

    @Test
    public void groovyStepExecutesViaDiscoveredFactory() {
        WorkflowExecutorService service = new WorkflowExecutorService(
                SpiWorkflowContextFactory.create());

        CodeData step = new CodeData();
        step.setName("groovy");
        step.setLanguage("groovy");
        step.setCode("x * 2");
        step.setAssignResultToVar("result");

        WorkflowData wf = new WorkflowData();
        wf.setName("wf");
        wf.addSteps(step);
        wf.setResultFrom("result");

        WorkflowResult result = service.executeWorkflow(wf, Map.of("x", 21));
        assertThat(result.isSuccess()).isTrue();
        assertThat(((Number) result.getResult()).intValue()).isEqualTo(42);
    }

    @Test
    public void customClassLoaderOverloadWorks() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        WorkflowContextFactory factory = SpiWorkflowContextFactory.create(cl);
        assertThat(factory).isNotNull();
        assertThat(factory.getStepInstanceFactory()).isNotNull();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private <T extends StepData> T namedStep(Class<T> cls) {
        try {
            T instance = cls.getDeclaredConstructor().newInstance();
            ((BaseStepData) instance).setName("test");
            return instance;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
