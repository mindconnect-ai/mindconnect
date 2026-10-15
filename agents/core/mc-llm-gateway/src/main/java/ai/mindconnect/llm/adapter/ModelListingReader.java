package ai.mindconnect.llm.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.time.Duration;

/**
 * One request for a model listing, as a form round-trip can afford it: short
 * timeouts, because "the server is not running" must come back as a hint and
 * not as a hang, and every failure as a reason the form can show. A URL is
 * shown without its query, which is where Gemini puts the key.
 */
public final class ModelListingReader {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(8);

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    /** @param httpClient a client to derive from: the reader shortens its timeouts but shares its connection pool */
    public ModelListingReader(OkHttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .readTimeout(READ_TIMEOUT)
                .callTimeout(READ_TIMEOUT.plus(CONNECT_TIMEOUT))
                .build();
        this.objectMapper = objectMapper;
    }

    /**
     * No HTTP conversation at all: nothing listens, the connection timed out,
     * or the URL is not one. A catalog with a second endpoint to try knows
     * from this not to try it.
     */
    public static final class Unreachable extends IOException {
        public Unreachable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * The JSON the listing answered.
     *
     * @throws Unreachable when no server answered
     * @throws IOException with the status in words when a server answered, but not with a listing
     */
    public JsonNode read(Request request) throws IOException {
        Response response;
        try {
            response = httpClient.newCall(request).execute();
        } catch (IOException | IllegalArgumentException e) {
            throw new Unreachable(e.getMessage() == null
                    ? shown(request.url()) + " unreachable" : e.getMessage(), e);
        }
        try (response) {
            if (!response.isSuccessful()) {
                throw new IOException(switch (response.code()) {
                    case 401, 403 -> "HTTP " + response.code() + " — the API key is not accepted";
                    case 404 -> "HTTP 404 — this endpoint serves no model list";
                    default -> "HTTP " + response.code() + " from " + shown(request.url());
                });
            }
            if (response.body() == null) throw new IOException("Empty answer from " + shown(request.url()));
            return objectMapper.readTree(response.body().byteStream());
        }
    }

    /** A request URL as it may be shown: without its query. */
    public static String shown(HttpUrl url) {
        return url.newBuilder().query(null).build().toString();
    }

    public static Integer intOrNull(JsonNode node) {
        return node.isNumber() ? node.asInt() : null;
    }

    /** {@code baseUrl} without trailing slashes and without an accidental {@code /v1}, which the adapters append themselves. */
    public static String withoutApiPath(String baseUrl) {
        String base = baseUrl;
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (base.endsWith("/v1")) base = base.substring(0, base.length() - 3);
        return base;
    }
}
