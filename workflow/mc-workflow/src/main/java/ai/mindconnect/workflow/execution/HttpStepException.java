package ai.mindconnect.workflow.execution;

import ai.mindconnect.workflow.domain.HttpCallData;

/**
 * Thrown by {@link HttpCallStep} when the server returns a non-2xx status
 * and {@code HttpCallData#isFailOnError()} is {@code true}.
 */
public class HttpStepException extends RuntimeException {

    private final int statusCode;
    private final String responseBody;

    public HttpStepException(int statusCode, String responseBody) {
        super("HTTP request failed with status " + statusCode + ": " + responseBody);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getResponseBody() {
        return responseBody;
    }
}
