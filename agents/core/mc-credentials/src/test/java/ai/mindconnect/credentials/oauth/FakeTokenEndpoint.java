package ai.mindconnect.credentials.oauth;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A token endpoint that answers what a test queues, and remembers what it was
 * asked.
 *
 * <p>The JDK's own HTTP server: this module deliberately excludes JUnit 4, and
 * MockWebServer's base class is one. Two canned responses and a recorded form
 * body do not need more than this.
 */
final class FakeTokenEndpoint implements AutoCloseable {

    private record Canned(int status, String body) { }

    private final HttpServer server;
    private final Deque<Canned> answers = new ArrayDeque<>();
    private final List<Map<String, String>> received = java.util.Collections.synchronizedList(new ArrayList<>());
    private volatile long delayMillis;

    FakeTokenEndpoint() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                received.add(split(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
            }
            pause();
            Canned answer = answers.isEmpty() ? new Canned(500, "{}") : answers.removeFirst();
            byte[] body = answer.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(answer.status(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/token";
    }

    FakeTokenEndpoint answers(String json) {
        answers.addLast(new Canned(200, json));
        return this;
    }

    FakeTokenEndpoint refuses(int status, String json) {
        answers.addLast(new Canned(status, json));
        return this;
    }

    /**
     * Takes this long over every answer — long enough for a second caller to
     * arrive while the first is still waiting.
     */
    FakeTokenEndpoint slow(long millis) {
        delayMillis = millis;
        return this;
    }

    /** How many requests have come in so far. */
    int requests() {
        return received.size();
    }

    private void pause() {
        if (delayMillis <= 0) return;
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The form of the request at {@code index}, by field name. */
    Map<String, String> request(int index) {
        return received.get(index);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    static Map<String, String> split(String encoded) {
        Map<String, String> values = new LinkedHashMap<>();
        if (encoded == null || encoded.isBlank()) {
            return values;
        }
        for (String pair : encoded.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            values.put(java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return values;
    }
}
