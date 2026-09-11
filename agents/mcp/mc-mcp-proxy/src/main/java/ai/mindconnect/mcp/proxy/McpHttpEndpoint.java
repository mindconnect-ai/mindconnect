package ai.mindconnect.mcp.proxy;

import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A remote MCP server reached over streamable HTTP.
 *
 * <p>{@code headers} are sent with every request — that is where an
 * {@code Authorization} or an API-key header goes. They are resolved
 * values, not references: the caller has already turned whatever the
 * registration said into concrete strings, and this object must not be
 * logged, cached by, or stored anywhere because of it.
 *
 * @param url             full endpoint URL, e.g. {@code https://mcp.example.com/mcp}
 * @param headers         headers sent with every request; ordered for a
 *                        reproducible request, and possibly secret
 * @param connectTimeout  how long to wait for the connection and the
 *                        {@code initialize} handshake
 * @param callTimeout     per-request timeout for {@code tools/call}
 */
public record McpHttpEndpoint(
        URI url,
        Map<String, String> headers,
        Duration connectTimeout,
        Duration callTimeout
) implements McpEndpoint {

    public McpHttpEndpoint {
        if (url == null) {
            throw new IllegalArgumentException("url required");
        }
        if (url.getScheme() == null || !url.getScheme().startsWith("http")) {
            throw new IllegalArgumentException("url must be http or https: " + url);
        }
        headers = headers == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        if (connectTimeout == null) connectTimeout = Duration.ofSeconds(30);
        if (callTimeout == null) callTimeout = Duration.ofSeconds(60);
    }

    /** The origin, for logs and error messages — never the full URL with its query. */
    public String origin() {
        return url.getScheme() + "://" + url.getAuthority();
    }
}
