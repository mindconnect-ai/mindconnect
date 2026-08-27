package ai.mindconnect.workflow.execution;

import ai.mindconnect.workflow.domain.HttpCallData;
import ai.mindconnect.workflow.domain.VariableAssignment;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Executes an HTTP call using the JDK 11+ {@link HttpClient} — zero extra
 * dependencies beyond the core engine.
 *
 * <p>All expression fields ({@code url}, header values, {@code body}) undergo
 * {@code ${var}} substitution before the request is sent.
 *
 * <p>The step result is the response body as a {@code String}.
 * Optionally the status code and headers can be stored in named variables
 * via {@code HttpCallData#getStatusCodeVar()} and
 * {@code HttpCallData#getResponseHeadersVar()}.
 */
public class HttpCallStep extends BaseStepInstance<HttpCallData> {

    // HttpClient is thread-safe and expensive to create — reuse when possible.
    // Each step creates its own for now; callers can replace via a subclass or
    // a custom factory if they need connection pooling / custom TLS.
    private HttpClient httpClient;

    // -----------------------------------------------------------------------
    // Extension point: override to supply a shared / custom client
    // -----------------------------------------------------------------------

    protected HttpClient buildClient(HttpCallData config) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL);
        if (config.getTimeoutMs() > 0) {
            builder.connectTimeout(Duration.ofMillis(config.getTimeoutMs()));
        }
        return builder.build();
    }

    // -----------------------------------------------------------------------
    // execute
    // -----------------------------------------------------------------------

    @Override
    public void execute() throws Exception {
        HttpCallData cfg = getConfig();
        httpClient = buildClient(cfg);

        String url    = resolveString(cfg.getUrl());
        String method = cfg.getMethod() != null ? cfg.getMethod().toUpperCase() : "GET";
        String body   = resolveString(cfg.getBody());

        logDebug("HTTP %s %s", method, url);

        HttpRequest request = buildRequest(url, method, body, cfg);
        HttpResponse<InputStream> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofInputStream());

        int status = response.statusCode();
        String responseBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
        logDebug("HTTP %s %s → %d (%d bytes)", method, url, status, responseBody.length());

        // Store optional side-channel variables
        storeSideChannelVars(cfg, status, response);

        if (cfg.isFailOnError() && (status < 200 || status >= 300)) {
            throw new HttpStepException(status, responseBody);
        }

        setResult(responseBody);
    }

    // -----------------------------------------------------------------------
    // Request building
    // -----------------------------------------------------------------------

    private HttpRequest buildRequest(String url, String method, String body, HttpCallData cfg)
            throws Exception {

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url));

        if (cfg.getTimeoutMs() > 0) {
            builder.timeout(Duration.ofMillis(cfg.getTimeoutMs()));
        }

        // Add headers
        for (VariableAssignment header : cfg.getHeaders()) {
            String name  = header.getVarName();
            String value = resolveString(header.getExpressionOrVarName());
            builder.header(name, value);
        }

        // Derive content-type
        String contentType = resolveString(cfg.getContentType());

        // Body publisher
        HttpRequest.BodyPublisher publisher;
        if (body != null && !body.isBlank()) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            publisher = HttpRequest.BodyPublishers.ofByteArray(bytes);
            if (contentType == null) {
                contentType = "application/json";
            }
        } else {
            publisher = HttpRequest.BodyPublishers.noBody();
        }

        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }

        builder.method(method, publisher);
        return builder.build();
    }

    // -----------------------------------------------------------------------
    // Side-channel variable storage
    // -----------------------------------------------------------------------

    private void storeSideChannelVars(HttpCallData cfg, int status,
                                       HttpResponse<InputStream> response) {
        if (cfg.getStatusCodeVar() != null && !cfg.getStatusCodeVar().isBlank()) {
            getVariableScope().assignValueToParentScope(cfg.getStatusCodeVar(), status, null);
            logDebug("stored status code %d → %s", status, cfg.getStatusCodeVar());
        }

        if (cfg.getResponseHeadersVar() != null && !cfg.getResponseHeadersVar().isBlank()) {
            Map<String, String> headers = new LinkedHashMap<>();
            response.headers().map().forEach((k, v) -> headers.put(k, String.join(", ", v)));
            getVariableScope().assignValueToParentScope(cfg.getResponseHeadersVar(), headers, null);
            logDebug("stored response headers → %s", cfg.getResponseHeadersVar());
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Resolves a string value, returns null if the input is null. */
    private String resolveString(String value) {
        if (value == null) return null;
        Object resolved = resolveExpression(value);
        return resolved == null ? null : resolved.toString();
    }
}
