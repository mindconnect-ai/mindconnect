package ai.mindconnect.workflow.test;

import ai.mindconnect.workflow.execution.DefaultWorkflowContextFactory;
import ai.mindconnect.workflow.execution.WorkflowContextFactory;
import ai.mindconnect.workflow.execution.WorkflowExecutorService;
import ai.mindconnect.workflow.code.beanshell.BeanShellWorkflowConfigurer;
import ai.mindconnect.workflow.code.groovy.GroovyWorkflowConfigurer;
import ai.mindconnect.workflow.code.javascript.JavaScriptWorkflowConfigurer;
import ai.mindconnect.workflow.code.jython.JythonWorkflowConfigurer;
import ai.mindconnect.workflow.jackson.JacksonWorkflowSerializer;
import ai.mindconnect.workflow.jackson.WorkflowObjectMapperFactory;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Shared setup utilities for integration tests.
 *
 * <p>Builds a {@link WorkflowContextFactory} by applying all available
 * module configurers in order: code engine, language engines, HTTP, SpEL.
 */
public final class WorkflowTestHelper {

    private WorkflowTestHelper() {}

    /**
     * Builds a fully configured {@link WorkflowContextFactory} with all
     * available step types and expression resolvers applied.
     */
    public static WorkflowContextFactory fullFactory() {
        WorkflowContextFactory ctx = new DefaultWorkflowContextFactory();
        // Language engines (register into the ScriptExecutor created above)
        new JavaScriptWorkflowConfigurer().configure(ctx);
        new GroovyWorkflowConfigurer().configure(ctx);
        new BeanShellWorkflowConfigurer().configure(ctx);
        new JythonWorkflowConfigurer().configure(ctx);
        return ctx;
    }

    /** ObjectMapper aware of all step types registered in this test module. */
    public static ObjectMapper objectMapper() {
        return WorkflowObjectMapperFactory.create();
    }

    /** Programmatic (no-JSON) executor with all step types and resolvers. */
    public static WorkflowExecutorService programmaticService() {
        return new WorkflowExecutorService(fullFactory());
    }

    /** Alias kept for tests that previously called programmaticServiceWithResolvers(). */
    public static WorkflowExecutorService programmaticServiceWithResolvers() {
        return programmaticService();
    }

    /**
     * Executor that loads workflow definitions from JSON on the classpath
     * ({@code /workflows/<id>.json}) and supports all step types.
     */
    public static WorkflowExecutorService classpathService() {
        WorkflowContextFactory ctx = fullFactory();
        return new WorkflowExecutorService(new DefaultWorkflowContextFactory());
    }

    /** Serializer for round-trip tests. */
    public static JacksonWorkflowSerializer serializer() {
        return new JacksonWorkflowSerializer(objectMapper());
    }
}
