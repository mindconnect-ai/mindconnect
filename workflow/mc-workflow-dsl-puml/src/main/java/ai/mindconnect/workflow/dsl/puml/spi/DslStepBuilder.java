package ai.mindconnect.workflow.dsl.puml.spi;

import ai.mindconnect.workflow.domain.StepData;

import java.util.Map;

/**
 * SPI for registering custom step builders with the PlantUML DSL parser.
 *
 * <p>Implement this interface and register via
 * {@code META-INF/services/ai.mindconnect.workflow.dsl.puml.spi.DslStepBuilder}
 * to add new step types without modifying the parser.
 *
 * <p>The parser matches the first word inside a {@code :..;} action block to
 * the type name returned by {@link #typeName()}.  The remaining properties are
 * passed as a {@code Map<String, String>}.
 *
 * <p>Example DSL line that invokes a builder with typeName {@code "http"}:
 * <pre>
 *   :http fetchUser
 *     url = https://api.example.com/users/${userId}
 *     method = GET
 *     result = userJson
 *   ;
 * </pre>
 *
 * <p>Built-in step types handled without a registered builder:
 * <ul>
 *   <li>{@code assign} — {@code AssignVariablesData}</li>
 *   <li>{@code halt} — {@code HaltData}</li>
 *   <li>{@code jump} — {@code JumpToData}</li>
 *   <li>{@code call} — {@code CallWorkflowData}</li>
 *   <li>{@code foreach} — {@code ForEachData} (via {@code fork} / {@code end fork})</li>
 * </ul>
 */
public interface DslStepBuilder {

    /**
     * The step-type keyword as it appears in the DSL (case-insensitive).
     * For example: {@code "http"}, {@code "code"}, {@code "groovy"}.
     */
    String typeName();

    /**
     * Builds a {@link StepData} from the parsed properties.
     *
     * @param name  the step name (second token on the first line of the action block)
     * @param props key/value pairs from the property lines inside the action block
     * @return the configured step data
     */
    StepData build(String name, Map<String, String> props);
}
