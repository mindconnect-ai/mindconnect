package ai.mindconnect.workflow.domain;

import ai.mindconnect.workflow.execution.HttpStepException;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for an HTTP call step.
 *
 * <p>Supports GET, POST, PUT, PATCH, DELETE.  The URL, headers, and body all
 * participate in {@code ${var}} expression substitution so they can be built
 * dynamically from workflow variables at runtime.
 *
 * <p>Example JSON (when used with mc-workflow-jackson):
 * <pre>{@code
 * {
 *   "@class": "ai.mindconnect.workflow.api.domain.HttpCallData",
 *   "name": "fetchUser",
 *   "url": "https://api.example.com/users/${userId}",
 *   "method": "GET",
 *   "assignResultToVar": "userResponse"
 * }
 * }</pre>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class HttpCallData extends BaseStepData {

    // -----------------------------------------------------------------------
    // Request
    // -----------------------------------------------------------------------

    /** Target URL — may contain {@code ${var}} placeholders. */
    private String url;

    /** HTTP method. Defaults to {@code GET}. */
    private String method = "GET";

    /**
     * Request headers as name/value pairs.
     * The {@code expressionOrVarName} field holds the header value (may contain {@code ${var}}).
     * The {@code varName} field holds the header name.
     */
    private List<VariableAssignment> headers = new ArrayList<>();

    /**
     * Request body as a plain string or {@code ${var}} expression.
     * Ignored for GET/DELETE unless explicitly set.
     */
    private String body;

    /**
     * Content-Type header value.  Set as a shorthand — equivalent to adding
     * a header entry named {@code Content-Type}.  Defaults to
     * {@code application/json} when a body is present.
     */
    private String contentType;

    // -----------------------------------------------------------------------
    // Response handling
    // -----------------------------------------------------------------------

    /**
     * When {@code true} the step throws {@link HttpStepException} for any
     * non-2xx response code.  Defaults to {@code true}.
     */
    private boolean failOnError = true;

    /**
     * Optional timeout in milliseconds.  {@code 0} means "use the client default".
     */
    private int timeoutMs = 0;

    /**
     * Optional variable name to store the raw HTTP status code (integer).
     * When null the status code is not stored separately — only the body is
     * assigned to {@code assignResultToVar}.
     */
    private String statusCodeVar;

    /**
     * Optional variable name to store the response headers as a
     * {@code Map<String, String>}.
     */
    private String responseHeadersVar;
}
