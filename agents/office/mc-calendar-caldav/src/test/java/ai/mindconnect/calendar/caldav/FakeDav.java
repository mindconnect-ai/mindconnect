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
    record Call(String method, String path, String authorization, String depth, String ifMatch, String body) { }

    /** One answer: a status (0 for the server's default), a body and an ETag header, if any. */
    private record Answer(int status, String body, String etag, String location) { }

    private final HttpServer server;
    private final List<Call> calls = new ArrayList<>();
    private final List<Answer> answers = new ArrayList<>();
    private int status = 207;

    FakeDav() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            calls.add(new Call(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Depth"),
                    exchange.getRequestHeaders().getFirst("If-Match"), body));
            Answer answer = answers.isEmpty() ? new Answer(0, "", null, null) : answers.remove(0);
            byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/xml; charset=utf-8");
            if (answer.etag() != null) exchange.getResponseHeaders().add("ETag", answer.etag());
            if (answer.location() != null) exchange.getResponseHeaders().add("Location", answer.location());
            exchange.sendResponseHeaders(answer.status() == 0 ? status : answer.status(),
                    bytes.length == 0 ? -1 : bytes.length);
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
        for (String body : bodies) answers.add(new Answer(0, body, null, null));
        return this;
    }

    /** The next answer, with its own status and an {@code ETag} header when {@code etag} is not null. */
    FakeDav answer(int status, String body, String etag) {
        answers.add(new Answer(status, body, etag, null));
        return this;
    }

    /** The next answer sends the client on to {@code location}. */
    FakeDav redirects(int status, String location) {
        answers.add(new Answer(status, "", null, location));
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
