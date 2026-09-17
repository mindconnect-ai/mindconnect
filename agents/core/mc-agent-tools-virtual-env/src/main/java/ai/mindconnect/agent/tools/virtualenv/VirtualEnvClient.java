package ai.mindconnect.agent.tools.virtualenv;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The calls the agent side makes to a virtual environment server: workspace
 * files by key, and acquire, poll and exec for commands. JDK HTTP client, JSON
 * via Jackson; every call carries the {@link TokenSource}'s token when there is one.
 */
public class VirtualEnvClient {

    /** A file, directory or link as the server reports it; {@code path} relative to the workspace root. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(String path, boolean directory, boolean regularFile, long size, String modifiedAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Tree(List<Entry> entries, boolean truncated) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Environment(String id, String state, int queuePosition, String message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExecResult(int exitCode, String output, boolean truncated, boolean timedOut, long durationMs) {
    }

    private final URI baseUrl;
    private final TokenSource tokens;
    private final Duration requestTimeout;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public VirtualEnvClient(URI baseUrl, TokenSource tokens, Duration requestTimeout) {
        String url = baseUrl.toString();
        this.baseUrl = URI.create(url.endsWith("/") ? url.substring(0, url.length() - 1) : url);
        this.tokens = tokens;
        this.requestTimeout = requestTimeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    URI baseUrl() {
        return baseUrl;
    }

    // ---- workspace files ------------------------------------------------------------------------

    public Optional<Entry> stat(WorkspaceKey key, String path) throws IOException {
        try {
            return Optional.of(json.readValue(send(key, "GET", workspace(key, "stat", path), null, null), Entry.class));
        } catch (VirtualEnvClientException e) {
            if (e.status() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    public List<Entry> list(WorkspaceKey key, String path) throws IOException {
        return json.readValue(send(key, "GET", workspace(key, "list", path), null, null), new TypeReference<>() {
        });
    }

    public Tree tree(WorkspaceKey key, String path, Set<String> excluded, int max) throws IOException {
        String uri = workspace(key, "tree", path) + "&exclude=" + encode(String.join(",", excluded)) + "&max=" + max;
        return json.readValue(send(key, "GET", uri, null, null), Tree.class);
    }

    public byte[] read(WorkspaceKey key, String path, Integer maxBytes) throws IOException {
        String uri = workspace(key, "content", path) + (maxBytes == null ? "" : "&maxBytes=" + maxBytes);
        return send(key, "GET", uri, null, null);
    }

    public void write(WorkspaceKey key, String path, byte[] content) throws IOException {
        send(key, "PUT", workspace(key, "content", path), content, "application/octet-stream");
    }

    // ---- environments ---------------------------------------------------------------------------

    public Environment acquire(WorkspaceKey key) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("template", key.template());
        body.put("sessionKey", key.sessionKey());
        return json.readValue(send(key, "PUT", "/v1/environments", json.writeValueAsBytes(body), "application/json"),
                Environment.class);
    }

    public Environment get(WorkspaceKey key, String id, int waitSeconds) throws IOException {
        return json.readValue(send(key, "GET", "/v1/environments/" + encode(id) + "?waitSeconds=" + waitSeconds,
                null, null), Environment.class);
    }

    public ExecResult exec(WorkspaceKey key, String id, String command, String stdin, Map<String, String> env,
                           long timeoutSeconds) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("command", command);
        body.put("timeoutSeconds", timeoutSeconds);
        body.put("env", env);
        body.put("stdin", stdin);
        return json.readValue(send(key, "POST", "/v1/environments/" + encode(id) + "/exec",
                json.writeValueAsBytes(body), "application/json", Duration.ofSeconds(timeoutSeconds + 60)),
                ExecResult.class);
    }

    // ---- plumbing -------------------------------------------------------------------------------

    private static String workspace(WorkspaceKey key, String operation, String path) {
        return "/v1/workspaces/" + encode(key.template()) + "/" + encode(key.sessionKey()) + "/" + operation
                + "?path=" + encode(path);
    }

    private byte[] send(WorkspaceKey key, String method, String pathAndQuery, byte[] body, String contentType)
            throws IOException {
        return send(key, method, pathAndQuery, body, contentType, requestTimeout);
    }

    private byte[] send(WorkspaceKey key, String method, String pathAndQuery, byte[] body, String contentType,
                        Duration timeout) throws IOException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + pathAndQuery))
                .timeout(timeout)
                .header("Accept", "application/json, application/octet-stream")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        tokens.token(key.scope()).ifPresent(token -> request.header("Authorization", "Bearer " + token));
        HttpResponse<byte[]> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VirtualEnvClientException("Interrupted while calling the virtual environment server", e);
        } catch (IOException e) {
            throw new VirtualEnvClientException("The virtual environment server cannot be reached ("
                    + baseUrl + "): " + e.getMessage(), e);
        }
        if (response.statusCode() >= 400) {
            throw new VirtualEnvClientException(response.statusCode(), message(response));
        }
        return response.body();
    }

    private String message(HttpResponse<byte[]> response) {
        try {
            Map<String, Object> error = json.readValue(response.body(), new TypeReference<>() {
            });
            Object message = error.get("message");
            if (message != null) {
                return String.valueOf(message);
            }
        } catch (IOException ignored) {
            // not JSON
        }
        return "HTTP " + response.statusCode();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
