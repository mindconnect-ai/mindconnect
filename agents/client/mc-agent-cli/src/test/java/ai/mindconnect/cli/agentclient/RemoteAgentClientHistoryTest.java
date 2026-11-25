package ai.mindconnect.cli.agentclient;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.management.api.ManagedMessage;
import okhttp3.OkHttpClient;

/** The history as the server writes it reads back into the messages the CLI shows. */
class RemoteAgentClientHistoryTest {

    private static final String HISTORY = """
            [ { "id": "m1", "sequence": 1, "sentAt": "2026-11-25T10:49:00Z",
                "message": { "id": "m1", "turnId": "t1", "type": "USER",
                             "content": [ { "kind": "text", "text": "What time is it?" } ],
                             "toolCalls": [], "metadata": {} },
                "durationMillis": null, "tokens": null },
              { "id": "m2", "sequence": 2, "sentAt": "2026-11-25T10:49:01Z",
                "message": { "id": "m2", "turnId": "t1", "type": "TOOL_CALL", "content": [],
                             "toolCalls": [ { "callId": "call_1", "name": "clock", "arguments": { "zone": "CET" } } ],
                             "metadata": { "callIds": [ "call_1" ] } },
                "durationMillis": 12, "tokens": 30 } ]
            """;

    private HttpServer server;

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void the_history_reads_back_with_places_times_and_the_messages_as_written() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/sessions/s-42/history", exchange -> {
            byte[] body = HISTORY.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        RemoteAgentClient client = new RemoteAgentClient("http://127.0.0.1:" + server.getAddress().getPort(),
                new OkHttpClient(), new ObjectMapper());

        List<ManagedMessage> history = client.loadHistory(SessionId.of("s-42"));

        assertThat(history).extracting(ManagedMessage::sequence).containsExactly(1, 2);
        assertThat(history.get(0).sentAt()).isEqualTo(Instant.parse("2026-11-25T10:49:00Z"));
        assertThat(history.get(0).message().type()).isEqualTo(TurnMessage.Type.USER);
        assertThat(history.get(0).message().text()).isEqualTo("What time is it?");
        assertThat(history.get(1).message().toolCalls()).containsExactly(
                new TurnMessage.ToolCall("call_1", "clock", java.util.Map.of("zone", "CET")));
        assertThat(history.get(1).durationMillis()).isEqualTo(12L);
        assertThat(history.get(1).tokens()).isEqualTo(30);
        assertThat(history.get(0).durationMillis()).isNull();
    }
}
