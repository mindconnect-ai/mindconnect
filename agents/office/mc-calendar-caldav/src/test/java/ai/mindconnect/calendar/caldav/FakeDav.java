package ai.mindconnect.calendar.caldav;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A CalDAV server that answers what a test tells it to, and remembers what it
 * was asked — the same shape as the fake Graph and Google servers upstream.
 */
final class FakeDav implements AutoCloseable {

    /** One request as the server saw it. */
    record Call(String method, String path, String authorization, String depth, String body) { }

    private final HttpServer server;
    private final List<Call> calls = new ArrayList<>();
    private final List<String> answers = new ArrayList<>();
    private int status = 207;

    FakeDav() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            calls.add(new Call(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Depth"), body));
            String answer = answers.isEmpty() ? "" : answers.remove(0);
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/xml; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    URI url(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    /** The next answer, and the one after it. */
    FakeDav answers(String... bodies) {
        answers.addAll(List.of(bodies));
        return this;
    }

    FakeDav refuses(int status) {
        this.status = status;
        return this;
    }

    Call call(int index) {
        return calls.get(index);
    }

    int calls() {
        return calls.size();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
