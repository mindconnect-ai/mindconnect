package ai.mindconnect.taskqueue.clusterdemo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * The nudge wire: fire-and-forget POSTs between the nodes. Deliberately
 * best-effort — a nudge that gets lost costs one poll interval, never a task
 * or an event, so failures are logged at debug and swallowed.
 */
@Component
public class ClusterHttp {

    private static final Logger log = LoggerFactory.getLogger(ClusterHttp.class);

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    public void nudge(String baseUrl, String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(2))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .exceptionally(e -> {
                    log.debug("Nudge {}{} not delivered: {}", baseUrl, path, e.getMessage());
                    return null;
                });
    }

    /** Synchronous liveness probe — the one call that wants the answer. */
    public boolean isUp(String baseUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/cluster/health"))
                    .timeout(Duration.ofSeconds(2))
                    .GET().build();
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
}
