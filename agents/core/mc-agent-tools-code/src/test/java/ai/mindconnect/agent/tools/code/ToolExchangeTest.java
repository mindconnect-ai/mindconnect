package ai.mindconnect.agent.tools.code;

import ai.mindconnect.agent.tool.FileRoots;
import ai.mindconnect.agent.tool.ScopedToolInvoker;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.workspace.WorkspaceFiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The directory is the whole protocol: a program drops a request in, the host
 * drops an answer beside it. These tests play the program — writing the files
 * a container would write — and check what comes back.
 */
class ToolExchangeTest {

    /** Long enough that the exchange's own thread stays out of the way; the test pumps. */
    private static final Duration MANUAL = Duration.ofSeconds(30);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path workspace;

    private final List<String> invoked = new ArrayList<>();

    @Test
    void aRequestIsAnsweredWithTheToolsResult() throws Exception {
        try (ToolExchange exchange = open(echo(), List.of("calendar_list"))) {
            request(exchange, "0001", "calendar_list", Map.of("from", "monday"));

            exchange.pump();

            JsonNode answer = response(exchange, "0001");
            assertThat(answer.get("ok").asBoolean()).isTrue();
            assertThat(answer.get("result").asText()).isEqualTo("calendar_list{from=monday}");
            assertThat(invoked).containsExactly("calendar_list");
        }
    }

    @Test
    void aToolTheProgramDidNotDeclareIsRefusedWithoutRunningIt() throws Exception {
        try (ToolExchange exchange = open(echo(), List.of("calendar_list"))) {
            request(exchange, "0001", "gmail_send", Map.of());

            exchange.pump();

            JsonNode answer = response(exchange, "0001");
            assertThat(answer.get("ok").asBoolean()).isFalse();
            assertThat(answer.get("error").asText())
                    .contains("not declared").contains("calendar_list");
            assertThat(invoked).isEmpty();
        }
    }

    @Test
    void aFailingToolComesBackAsAnError() throws Exception {
        ScopedToolInvoker refusing = invoker((tool, args) ->
                ScopedToolInvoker.Result.failure("Error: the user did not approve this tool call"));
        try (ToolExchange exchange = open(refusing, List.of("gmail_send"))) {
            request(exchange, "0001", "gmail_send", Map.of());

            exchange.pump();

            JsonNode answer = response(exchange, "0001");
            assertThat(answer.get("ok").asBoolean()).isFalse();
            assertThat(answer.get("error").asText()).contains("did not approve");
        }
    }

    @Test
    void anUnreadableRequestIsAnsweredInsteadOfIgnored() throws Exception {
        try (ToolExchange exchange = open(echo(), List.of("calendar_list"))) {
            // Half a file, as a program that crashed mid-write would leave it —
            // except the marker says it is complete, so the host must cope.
            write(exchange, "0001.request.json", "{\"tool\": \"calendar_li");
            write(exchange, "0001.request.json.ready", "");

            exchange.pump();

            JsonNode answer = response(exchange, "0001");
            assertThat(answer.get("ok").asBoolean()).isFalse();
            assertThat(answer.get("error").asText()).contains("could not be read as JSON");
        }
    }

    @Test
    void aRequestWithoutItsMarkerIsNotAnsweredYet() throws Exception {
        try (ToolExchange exchange = open(echo(), List.of("calendar_list"))) {
            write(exchange, "0001.request.json", "{\"tool\":\"calendar_list\",\"arguments\":{}}");

            exchange.pump();

            assertThat(Files.exists(exchange.directory().resolve("0001.response.json"))).isFalse();
            assertThat(invoked).isEmpty();
        }
    }

    @Test
    void everyRequestIsAnsweredOnce_evenWhenThePollerSeesItAgain() throws Exception {
        try (ToolExchange exchange = open(echo(), List.of("calendar_list"))) {
            request(exchange, "0001", "calendar_list", Map.of());

            exchange.pump();
            exchange.pump();
            exchange.pump();

            assertThat(invoked).containsExactly("calendar_list");
        }
    }

    @Test
    void requestsAreAnsweredInTheOrderTheProgramNumberedThem() throws Exception {
        try (ToolExchange exchange = open(echo(), List.of("a", "b", "c"))) {
            request(exchange, "0002", "b", Map.of());
            request(exchange, "0003", "c", Map.of());
            request(exchange, "0001", "a", Map.of());

            exchange.pump();

            assertThat(invoked).containsExactly("a", "b", "c");
        }
    }

    @Test
    void aResultTooBigForAFileIsRefusedRatherThanWritten() throws Exception {
        String huge = "x".repeat(ToolExchange.MAX_RESPONSE_BYTES + 1);
        ScopedToolInvoker big = invoker((tool, args) -> new ScopedToolInvoker.Result(huge, false, 1L));
        try (ToolExchange exchange = open(big, List.of("dump"))) {
            request(exchange, "0001", "dump", Map.of());

            exchange.pump();

            JsonNode answer = response(exchange, "0001");
            assertThat(answer.get("ok").asBoolean()).isFalse();
            assertThat(answer.get("error").asText()).contains("over the limit");
        }
    }

    @Test
    void whatTheProgramCalledIsKeptForTheOuterResultsMetadata() throws Exception {
        try (ToolExchange exchange = open(echo(), List.of("calendar_list", "gmail_send"))) {
            request(exchange, "0001", "calendar_list", Map.of());
            request(exchange, "0002", "nowhere_near", Map.of());
            exchange.pump();

            List<ToolExchange.Call> calls = exchange.calls();

            assertThat(calls).hasSize(2);
            assertThat(calls.get(0).tool()).isEqualTo("calendar_list");
            assertThat(calls.get(0).ok()).isTrue();
            assertThat(calls.get(1).ok()).isFalse();
        }
    }

    @Test
    void theExchangeAnswersOnItsOwnThreadWhileTheProgramWaits() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        ScopedToolInvoker slow = invoker((tool, args) -> {
            running.countDown();
            sleep(200);
            return new ScopedToolInvoker.Result("done", false, 200L);
        });
        try (ToolExchange exchange = ToolExchange.open(files(), workspace, "exec-1", slow,
                ToolCallScope.detached(null), "code_execute", List.of("slow"), Duration.ofMillis(10))) {
            request(exchange, "0001", "slow", Map.of());

            assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
            // The host is working and the program is blocked: its clock must not run.
            assertThat(exchange.requestPending()).isTrue();

            JsonNode answer = await(exchange, "0001");
            assertThat(answer.get("result").asText()).isEqualTo("done");
            assertThat(exchange.requestPending()).isFalse();
        }
    }

    @Test
    void closingTakesTheDirectoryAway() throws Exception {
        ToolExchange exchange = open(echo(), List.of("calendar_list"));
        request(exchange, "0001", "calendar_list", Map.of());
        exchange.pump();
        Path dir = exchange.directory();
        assertThat(Files.isDirectory(dir)).isTrue();

        exchange.close();

        assertThat(Files.exists(dir)).isFalse();
    }

    @Test
    void theDirectoryIsWhereTheProgramIsToldToLook() throws Exception {
        try (ToolExchange exchange = open(echo(), List.of())) {
            assertThat(exchange.directory())
                    .isEqualTo(workspace.resolve(ToolExchange.DIR_NAME).resolve("exec-1"));
            assertThat(exchange.env()).containsEntry(ToolExchange.DIR_ENV, exchange.directory().toString());
        }
    }

    @Test
    void withoutADeclarationEveryToolTheInvokerAllowsGoesThrough() throws Exception {
        // The declaration is the approval's business; an empty one means the
        // caller did not gate, and the invoker remains the only judge.
        try (ToolExchange exchange = open(echo(), List.of())) {
            request(exchange, "0001", "anything", Map.of());

            exchange.pump();

            assertThat(response(exchange, "0001").get("ok").asBoolean()).isTrue();
            assertThat(invoked).containsExactly("anything");
        }
    }

    // ── playing the program ─────────────────────────────────────────────────

    private ToolExchange open(ScopedToolInvoker invoker, List<String> declared) throws IOException {
        return ToolExchange.open(files(), workspace, "exec-1", invoker,
                ToolCallScope.detached(null), "code_execute", declared, MANUAL);
    }

    private WorkspaceFiles files() {
        return WorkspaceFiles.local(FileRoots.of(workspace));
    }

    /** Content first, marker second — the same order a program's {@code mc_tools} uses. */
    private void request(ToolExchange exchange, String id, String tool, Map<String, Object> arguments)
            throws IOException {
        write(exchange, id + ".request.json",
                MAPPER.writeValueAsString(Map.of("tool", tool, "arguments", arguments)));
        write(exchange, id + ".request.json.ready", "");
    }

    private void write(ToolExchange exchange, String name, String content) throws IOException {
        Files.write(exchange.directory().resolve(name), content.getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode response(ToolExchange exchange, String id) throws IOException {
        Path file = exchange.directory().resolve(id + ".response.json");
        assertThat(Files.exists(exchange.directory().resolve(id + ".response.json.ready")))
                .as("the marker beside %s", file).isTrue();
        return MAPPER.readTree(Files.readAllBytes(file));
    }

    private JsonNode await(ToolExchange exchange, String id) throws Exception {
        Path marker = exchange.directory().resolve(id + ".response.json.ready");
        for (int i = 0; i < 200 && !Files.exists(marker); i++) {
            sleep(25);
        }
        return response(exchange, id);
    }

    private interface Run {
        ScopedToolInvoker.Result apply(String tool, Map<String, Object> arguments);
    }

    /** Echoes the call, and remembers it, so a test can say what the host really ran. */
    private ScopedToolInvoker echo() {
        return invoker((tool, args) -> new ScopedToolInvoker.Result(tool + args, false, 1L));
    }

    private ScopedToolInvoker invoker(Run run) {
        return new ScopedToolInvoker() {
            @Override
            public Result invoke(ToolCallScope scope, String callerToolName, String toolName,
                                 Map<String, Object> arguments) {
                synchronized (invoked) {
                    invoked.add(toolName);
                }
                return run.apply(toolName, arguments);
            }

            @Override
            public List<String> callableTools(ToolCallScope scope, String callerToolName) {
                return List.of();
            }
        };
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
