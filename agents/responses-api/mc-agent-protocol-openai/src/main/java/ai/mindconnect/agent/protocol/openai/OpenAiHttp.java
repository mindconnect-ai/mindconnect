package ai.mindconnect.agent.protocol.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Minimal OpenAI HTTP wrapper: JSON in/out plus a line-based SSE reader. JDK client only. */
final class OpenAiHttp {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    private final ObjectMapper json = new ObjectMapper();
    private final String baseUrl;
    private final String apiKey;

    OpenAiHttp(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    JsonNode get(String path) {
        return send(request(path).GET().build());
    }

    JsonNode post(String path, Object body) {
        try {
            String payload = json.writeValueAsString(body);
            return send(request(path)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build());
        } catch (IOException e) {
            throw new OpenAiBackendException("Failed to serialize request body", e);
        }
    }

    /** Multipart form upload (the /files endpoint). */
    JsonNode postMultipart(String path, Map<String, String> fields,
                           String fileField, String filename, String fileContentType, byte[] file) {
        String boundary = "mc-" + UUID.randomUUID();
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            for (Map.Entry<String, String> field : fields.entrySet()) {
                body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\""
                        + field.getKey() + "\"\r\n\r\n" + field.getValue() + "\r\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + fileField
                    + "\"; filename=\"" + filename + "\"\r\nContent-Type: " + fileContentType
                    + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(file);
            body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return send(request(path)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                    .build());
        } catch (IOException e) {
            throw new OpenAiBackendException("Failed to build multipart request", e);
        }
    }

    /**
     * Opens an SSE stream and hands every {@code data:} JSON payload to
     * {@code onData} from a dedicated virtual thread. Closing the returned
     * handle stops reading and tears the connection down.
     */
    AutoCloseable stream(String path, Consumer<JsonNode> onData) {
        AtomicBoolean open = new AtomicBoolean(true);
        Thread reader = Thread.ofVirtual().name("openai-sse").start(() -> {
            HttpRequest req = request(path).header("Accept", "text/event-stream").GET().build();
            try {
                HttpResponse<Stream<String>> res = http.send(req, HttpResponse.BodyHandlers.ofLines());
                try (Stream<String> lines = res.body()) {
                    lines.takeWhile(l -> open.get()).forEach(line -> {
                        if (!line.startsWith("data:")) return;
                        String data = line.substring(5).trim();
                        if (data.isEmpty() || "[DONE]".equals(data)) return;
                        try {
                            onData.accept(json.readTree(data));
                        } catch (IOException ignored) {
                            // malformed frame — skip, stream continues
                        }
                    });
                }
            } catch (IOException | InterruptedException ignored) {
                // closed subscription or dropped connection ends the reader
            }
        });
        return () -> {
            open.set(false);
            reader.interrupt();
        };
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofMinutes(10));
    }

    private JsonNode send(HttpRequest req) {
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode node = res.body() == null || res.body().isBlank()
                    ? json.createObjectNode() : json.readTree(res.body());
            if (res.statusCode() >= 400) {
                throw new OpenAiBackendException("OpenAI " + res.statusCode() + ": "
                        + node.path("error").path("message").asText(res.body()), null);
            }
            return node;
        } catch (IOException e) {
            throw new OpenAiBackendException("OpenAI call failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpenAiBackendException("Interrupted during OpenAI call", e);
        }
    }
}
