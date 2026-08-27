package ai.mindconnect.workflow.jackson;

import ai.mindconnect.workflow.execution.CompositeExpressionResolver;
import ai.mindconnect.workflow.execution.WorkflowConfigurer;
import ai.mindconnect.workflow.execution.WorkflowContextFactory;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@link WorkflowConfigurer} that adds {@code json:} expression support to
 * the workflow engine.
 *
 * <p>After this configurer runs, any string value prefixed with {@code json:}
 * is parsed as a JSON literal and the resulting object (Map, List, scalar) is
 * used directly as the variable value.  This works in input parameters,
 * {@code AssignVariablesData} values, and anywhere else the expression
 * resolver is applied.
 *
 * <p>Examples:
 * <pre>
 *   json: {"name":"David","age":40}   → Map
 *   json: ["a","b","c"]               → List
 *   json: 42                          → Integer
 *   json: true                        → Boolean
 * </pre>
 *
 * <p>The configurer appends the {@link JsonExpressionResolver} to an existing
 * {@link CompositeExpressionResolver} when one is already installed, or wraps
 * the existing resolver in a new {@code CompositeExpressionResolver} if not.
 * If no resolver is set at all, it installs the {@code JsonExpressionResolver}
 * directly.
 *
 * <p>This class has a public no-arg constructor so it can be discovered via
 * {@link java.util.ServiceLoader} when listed in
 * {@code META-INF/services/ai.mindconnect.workflow.api.execution.WorkflowConfigurer}.
 */
public class JsonWorkflowConfigurer implements WorkflowConfigurer {

    /** Used by ServiceLoader. */
    public JsonWorkflowConfigurer() {}

    @Override
    public void configure(WorkflowContextFactory factory) {
        ObjectMapper mapper = WorkflowObjectMapperFactory.create();
        JsonExpressionResolver jsonResolver = new JsonExpressionResolver(mapper);

        CompositeExpressionResolver existing = factory.getExpressionResolver();
        existing.add(jsonResolver);

        var serializer = new JacksonWorkflowSerializer(mapper);
        factory.setJsonMapper(new JacksonJsonMapper(mapper));
        factory.setWorkflowDefinitionRegistry(
                new JacksonWorkflowDefinitionRegistry(serializer));


    }
}
