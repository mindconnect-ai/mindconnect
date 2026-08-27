package ai.mindconnect.workflow.dsl.puml.spi;

import ai.mindconnect.workflow.domain.VariableAssignment;
import ai.mindconnect.workflow.domain.HttpCallData;

import java.util.Map;

/**
 * Builds {@link HttpCallData} from DSL property lines.
 *
 * <p>Supported properties:
 * <ul>
 *   <li>{@code url} — target URL (may contain {@code ${var}} placeholders)</li>
 *   <li>{@code method} — HTTP method (default: {@code GET})</li>
 *   <li>{@code body} — request body string or expression</li>
 *   <li>{@code contentType} — Content-Type header shorthand</li>
 *   <li>{@code failOnError} — {@code true/false} (default: {@code true})</li>
 *   <li>{@code timeoutMs} — timeout in milliseconds</li>
 *   <li>{@code statusCodeVar} — variable to store HTTP status code</li>
 *   <li>{@code responseHeadersVar} — variable to store response headers</li>
 *   <li>{@code result} — sets {@code assignResultToVar}</li>
 *   <li>{@code header.<name>} — adds a request header; e.g. {@code header.Authorization = Bearer ${token}}</li>
 * </ul>
 *
 * <p>Example:
 * <pre>
 *   :http fetchUser
 *     url = https://api.example.com/users/${userId}
 *     method = GET
 *     header.Accept = application/json
 *     result = userJson
 *   ;
 * </pre>
 */
public class HttpDslStepBuilder implements DslStepBuilder {

    private static final String HEADER_PREFIX = "header.";

    @Override
    public String typeName() {
        return "http";
    }

    @Override
    public HttpCallData build(String name, Map<String, String> props) {
        HttpCallData data = new HttpCallData();
        data.setName(name);
        for (Map.Entry<String, String> entry : props.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key.toLowerCase().startsWith(HEADER_PREFIX)) {
                String headerName = key.substring(HEADER_PREFIX.length());
                data.getHeaders().add(new VariableAssignment(headerName, value));
            } else {
                switch (key.toLowerCase()) {
                    case "url" -> data.setUrl(value);
                    case "method" -> data.setMethod(value.toUpperCase());
                    case "body" -> data.setBody(value);
                    case "contenttype" -> data.setContentType(value);
                    case "failonerror" -> data.setFailOnError(Boolean.parseBoolean(value));
                    case "timeoutms" -> data.setTimeoutMs(Integer.parseInt(value));
                    case "statuscodevar" -> data.setStatusCodeVar(value);
                    case "responseheadersvar" -> data.setResponseHeadersVar(value);
                    case "assign-result", "result" -> data.setAssignResultToVar(value);
                    default -> { /* ignore unknown */ }
                }
            }
        }
        return data;
    }
}
