package ai.mindconnect.agent.registry.adapter.github;

import ai.mindconnect.agent.registry.domain.RegistryException;
import ai.mindconnect.agent.registry.domain.RegistryIndex;
import ai.mindconnect.agent.registry.domain.RegistryPackage;
import ai.mindconnect.agent.registry.domain.RegistrySource;
import ai.mindconnect.agent.registry.port.out.RegistryClient;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Reads a registry as what it is: raw files in a GitHub repository.
 *
 * <p>{@code https://raw.githubusercontent.com/{owner}/{repo}/{ref}/{path}} —
 * no API, no rate limit worth worrying about for a public repository, and
 * nothing to run on the registry's side. A private repository is the same URL
 * with a token, which the source names by environment variable so that the
 * store holding the source can never hold the secret.
 *
 * <p>Answers are cached for a TTL, because a screen that lists a registry and
 * then shows one of its entries would otherwise fetch the index twice for one
 * pair of clicks. The cache is dropped by {@link #refresh}, which is what the
 * screen's refresh button calls — a registry changes when its owner pushes,
 * and nothing here can know when that was.
 */
public class GitHubRegistryClient implements RegistryClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubRegistryClient.class);

    /**
     * Cap on a single fetched file. A registry entry is a JSON document
     * somebody wrote by hand; anything past this is not one, and reading it
     * into memory because a URL pointed at it is how a browse turns into an
     * outage.
     */
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    private final HttpClient http;
    private final ObjectMapper objectMapper;
    private final Duration ttl;
    private final Duration timeout;
    /** Where a token named by a source is looked up — the process environment, or a test's map. */
    private final Function<String, String> env;

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public GitHubRegistryClient() {
        this(Duration.ofMinutes(10), Duration.ofSeconds(20));
    }

    public GitHubRegistryClient(Duration ttl, Duration timeout) {
        this(ttl, timeout, System::getenv);
    }

    public GitHubRegistryClient(Duration ttl, Duration timeout, Function<String, String> env) {
        this.ttl = ttl == null ? Duration.ZERO : ttl;
        this.timeout = timeout == null ? Duration.ofSeconds(20) : timeout;
        this.env = env == null ? name -> null : env;
        this.objectMapper = new ObjectMapper()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.http = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public RegistryIndex fetchIndex(RegistrySource source) {
        return parse(source, source.indexPath(), RegistryIndex.class, "index");
    }

    @Override
    public RegistryPackage fetchPackage(RegistrySource source, String path) {
        return parse(source, path, RegistryPackage.class, "package manifest");
    }

    @Override
    public String fetchText(RegistrySource source, String path) {
        String url = rawUrl(source, path);
        CacheEntry cached = cache.get(url);
        if (cached != null && cached.isFresh(ttl)) {
            return cached.body();
        }
        String body = get(source, url);
        cache.put(url, new CacheEntry(body, Instant.now()));
        return body;
    }

    @Override
    public void refresh(RegistrySource source) {
        String prefix = rawUrl(source, "");
        cache.keySet().removeIf(url -> url.startsWith(prefix));
        log.debug("Dropped the cached files of {}", source.coordinates());
    }

    private <T> T parse(RegistrySource source, String path, Class<T> type, String what) {
        String body = fetchText(source, path);
        try {
            T parsed = objectMapper.readValue(body, type);
            if (parsed == null) {
                throw new RegistryException("The " + what + " of " + source.coordinates() + " is empty");
            }
            return parsed;
        } catch (RegistryException e) {
            throw e;
        } catch (Exception e) {
            throw new RegistryException("The " + what + " of " + source.coordinates()
                    + " (" + path + ") could not be read: " + e.getMessage(), e);
        }
    }

    /**
     * The raw URL of one repository file. Every path segment is encoded, and a
     * path that tries to climb out of the repository is refused here: entries
     * come from a file somebody else wrote, and {@code ../../} in one of them
     * must not turn into a request to another repository.
     */
    String rawUrl(RegistrySource source, String path) {
        String relative = path == null ? "" : path.strip();
        if (relative.startsWith("/")) relative = relative.substring(1);
        if (relative.contains("..") || relative.contains("://")) {
            throw new RegistryException("'" + path + "' is not a path inside a registry repository");
        }
        StringBuilder url = new StringBuilder(trimSlash(source.baseUrl()))
                .append('/').append(encode(source.owner()))
                .append('/').append(encode(source.repo()))
                .append('/').append(encode(source.ref()))
                .append('/');
        if (!relative.isEmpty()) {
            String[] segments = relative.split("/");
            for (int i = 0; i < segments.length; i++) {
                if (i > 0) url.append('/');
                url.append(encode(segments[i]));
            }
        }
        return url.toString();
    }

    private String get(RegistrySource source, String url) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Accept", "text/plain, application/json, */*")
                .header("User-Agent", "mindconnect-registry")
                .GET();
        String token = token(source);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        HttpResponse<byte[]> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new RegistryException("Registry " + source.coordinates() + " is unreachable: "
                    + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RegistryException("Reading registry " + source.coordinates() + " was interrupted", e);
        }
        if (response.statusCode() == 404) {
            throw new RegistryException("Registry " + source.coordinates() + " has no " + fileOf(url)
                    + (token == null && source.tokenEnvVar() == null
                            ? " (a private repository needs a token)" : ""));
        }
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new RegistryException("Registry " + source.coordinates() + " refused the request ("
                    + response.statusCode() + ")"
                    + (token == null ? " — it may need a token" : " — check the token in "
                            + source.tokenEnvVar()));
        }
        if (response.statusCode() / 100 != 2) {
            throw new RegistryException("Registry " + source.coordinates() + " answered "
                    + response.statusCode() + " for " + fileOf(url));
        }
        byte[] body = response.body();
        if (body.length > MAX_BYTES) {
            throw new RegistryException(fileOf(url) + " of registry " + source.coordinates()
                    + " is larger than " + (MAX_BYTES / (1024 * 1024)) + " MB — refusing to read it");
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    /** The token a source names, from the environment — {@code null} for a public repository. */
    private String token(RegistrySource source) {
        if (source.tokenEnvVar() == null) return null;
        String value = env.apply(source.tokenEnvVar());
        if (value == null || value.isBlank()) {
            log.warn("Registry {} names the token variable {}, which is not set",
                    source.coordinates(), source.tokenEnvVar());
            return null;
        }
        return value.strip();
    }

    private static String fileOf(String url) {
        int slash = url.lastIndexOf('/');
        return slash < 0 ? url : url.substring(slash + 1);
    }

    private static String trimSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /** Path segments are encoded, but a space becomes {@code %20}, not {@code +}. */
    private static String encode(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private record CacheEntry(String body, Instant fetchedAt) {
        boolean isFresh(Duration ttl) {
            return !ttl.isZero() && fetchedAt.plus(ttl).isAfter(Instant.now());
        }
    }
}
