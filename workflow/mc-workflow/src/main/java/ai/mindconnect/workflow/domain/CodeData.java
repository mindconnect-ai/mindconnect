package ai.mindconnect.workflow.domain;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Configuration for a code-execution step.
 *
 * <p>Supports JavaScript (Nashorn/GraalJS via JDK {@code ScriptEngine}),
 * Groovy, and any other language registered under a JSR-223 engine name.
 *
 * <p>The script receives all current workflow variables as top-level bindings
 * and may return a value directly.  The returned value becomes the step result
 * and is optionally stored in a variable via {@code assignResultToVar}.
 *
 * <p>Example JSON (when used with mc-workflow-jackson):
 * <pre>{@code
 * {
 *   "@class": "ai.mindconnect.workflow.api.domain.CodeData",
 *   "name": "calc",
 *   "language": "javascript",
 *   "code": "x + y",
 *   "assignResultToVar": "sum"
 * }
 * }</pre>
 *
 * <p>Language names follow JSR-223 engine names:
 * <ul>
 *   <li>{@code mini} — Miniscript which is already provided as dependency which is very lightweight </li>
 *   <li>{@code javascript} / {@code js} — Nashorn (JDK ≤ 14) or GraalJS</li>
 *   <li>{@code groovy} — requires groovy-jsr223 on the classpath</li>
 *   <li>{@code python} / {@code jython} — requires Jython on the classpath</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class CodeData extends BaseStepData {

    /**
     * JSR-223 engine name. Defaults to {@code javascript}.
     */
    private String language = "javascript";

    /**
     * The code to execute.  Must be a complete script fragment.
     * The last evaluated expression is used as the result.
     */
    private String code;

    /**
     * When {@code true}, wraps the {@code code} string in a function so that
     * {@code return} statements work inside single-expression scripts.
     * Defaults to {@code false}.
     *
     * <p>When {@code true}, the engine evaluates:
     * <pre>{@code (function(){ return <code>; })() }</pre>
     */
    private boolean wrapInFunction = false;

    /**
     * When {@code true} (default), all current workflow variables are injected
     * into the script's {@link javax.script.Bindings} so the script can read
     * them directly by name.
     */
    private boolean injectVariables = true;

    /**
     * When {@code true} (default), any variable the script writes to the
     * bindings is propagated back into the workflow's variable scope after
     * the script completes.
     */
    private boolean exportVariables = true;
}
