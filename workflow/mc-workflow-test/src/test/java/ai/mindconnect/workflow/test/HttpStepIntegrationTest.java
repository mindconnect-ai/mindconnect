package ai.mindconnect.workflow.test;

import ai.mindconnect.workflow.domain.*;
import ai.mindconnect.workflow.execution.HttpStepException;
import ai.mindconnect.workflow.execution.StepExecutionException;
import ai.mindconnect.workflow.execution.WorkflowExecutorService;
import ai.mindconnect.workflow.execution.WorkflowResult;
import com.sun.net.httpserver.HttpServer;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Integration tests combining HTTP steps with other step types.
 * Uses an embedded JDK HttpServer — no external dependencies.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class HttpStepIntegrationTest {

    private static HttpServer server;
    private static int port;

    @BeforeAll
    static void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();

        server.createContext("/api/hello", exchange -> {
            byte[] body = "{\"message\":\"hello world\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });

        server.createContext("/api/user", exchange -> {
            String query = exchange.getRequestURI().getQuery(); // id=123
            String id = "unknown";
            if (query != null) {
                for (String part : query.split("&")) {
                    if (part.startsWith("id=")) id = part.substring(3);
                }
            }
            byte[] body = ("{\"id\":\"" + id + "\",\"name\":\"User-" + id + "\",\"active\":true}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });

        server.createContext("/api/scores", exchange -> {
            byte[] body = "[10, 20, 30, 40, 50]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });

        server.createContext("/api/echo", exchange -> {
            byte[] req = exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, req.length);
            exchange.getResponseBody().write(req);
            exchange.getResponseBody().close();
        });

        server.createContext("/api/fail", exchange -> {
            byte[] body = "{\"error\":\"not found\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });

        server.start();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) server.stop(0);
    }

    private String base() { return "http://localhost:" + port; }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    @Order(1)
    public void httpGetThenAssignVariable() {
        WorkflowExecutorService service = WorkflowTestHelper.programmaticService();

        HttpCallData fetch = new HttpCallData();
        fetch.setName("fetch");
        fetch.setUrl(base() + "/api/user?id=${userId}");
        fetch.setMethod("GET");
        fetch.setAssignResultToVar("userJson");

        AssignVariablesData label = new AssignVariablesData();
        label.setName("label");
        label.getVariableAssignments()
                .add(new VariableAssignment("message", "Got user: ${userJson}"));
        label.setAssignResultToVar("message");

        WorkflowData wf = new WorkflowData();
        wf.setName("wf_http_assign");
        wf.addSteps(fetch, label);
        wf.setResultFrom("message");

        WorkflowResult result = service.executeWorkflow(wf, Map.of("userId", "42"));

        Assertions.assertThat(result.isSuccess()).isTrue();
        Assertions.assertThat(result.getResult().toString())
                .contains("Got user:")
                .contains("User-42");
    }

    @Test
    @Order(2)
    public void httpGetThenJavaScriptTransform() {
        WorkflowExecutorService service = WorkflowTestHelper.programmaticService();

        HttpCallData fetch = new HttpCallData();
        fetch.setName("fetch");
        fetch.setUrl(base() + "/api/user?id=7");
        fetch.setMethod("GET");
        fetch.setAssignResultToVar("userJson");

        // Parse the JSON response and extract the name field via JS string ops
        CodeData extract = new CodeData();
        extract.setName("extract");
        extract.setLanguage("javascript");
        extract.setWrapInFunction(false);
        extract.setCode("JSON.parse(userJson).name");
        extract.setAssignResultToVar("userName");

        WorkflowData wf = new WorkflowData();
        wf.setName("wf_http_js");
        wf.addSteps(fetch, extract);
        wf.setResultFrom("userName");

        WorkflowResult result = service.executeWorkflow(wf, Map.of());

        Assertions.assertThat(result.isSuccess()).isTrue();
        Assertions.assertThat(result.getResult().toString()).isEqualTo("User-7");
    }

    @Test
    @Order(3)
    public void httpPostWithBodyBuiltFromVariables() {
        WorkflowExecutorService service = WorkflowTestHelper.programmaticService();

        HttpCallData post = new HttpCallData();
        post.setName("post");
        post.setUrl(base() + "/api/echo");
        post.setMethod("POST");
        post.setBody("{\"greeting\":\"Hello ${name}\"}");
        post.setAssignResultToVar("echoed");

        WorkflowData wf = new WorkflowData();
        wf.setName("wf_http_post");
        wf.addSteps(post);
        wf.setResultFrom("echoed");

        WorkflowResult result = service.executeWorkflow(wf, Map.of("name", "World"));

        Assertions.assertThat(result.isSuccess()).isTrue();
        Assertions.assertThat(result.getResult().toString()).contains("Hello World");
    }

    @Test
    @Order(4)
    public void httpFailureHaltsWorkflowByDefault() {
        WorkflowExecutorService service = WorkflowTestHelper.programmaticService();

        HttpCallData fail = new HttpCallData();
        fail.setName("fail");
        fail.setUrl(base() + "/api/fail");
        fail.setMethod("GET");
        fail.setFailOnError(true);

        WorkflowData wf = new WorkflowData();
        wf.setName("wf_http_fail");
        wf.addSteps(fail);

        WorkflowResult result = service.executeWorkflow(wf, Map.of());

        Assertions.assertThat(result.isError()).isTrue();
        StepExecutionException stepEx = (StepExecutionException) result.getError();
        Assertions.assertThat(stepEx.getCausingException()).isInstanceOf(HttpStepException.class);
        Assertions.assertThat(((HttpStepException) stepEx.getCausingException()).getStatusCode())
                .isEqualTo(404);
    }

    @Test
    @Order(5)
    public void httpStatusCodeCapturedInVariable() {
        WorkflowExecutorService service = WorkflowTestHelper.programmaticService();

        // Fetch but don't fail on error — capture status code in a variable
        HttpCallData fetch = new HttpCallData();
        fetch.setName("fetch");
        fetch.setUrl(base() + "/api/fail");
        fetch.setMethod("GET");
        fetch.setFailOnError(false);
        fetch.setStatusCodeVar("status");
        fetch.setAssignResultToVar("body");

        // Assign a verdict based on the captured status code
        AssignVariablesData verdict = new AssignVariablesData();
        verdict.setName("verdict");
        verdict.getVariableAssignments()
                .add(new VariableAssignment("result", "statusWas:${status}"));
        verdict.setAssignResultToVar("result");

        WorkflowData wf = new WorkflowData();
        wf.setName("wf_status_capture");
        wf.addSteps(fetch, verdict);
        wf.setResultFrom("result");

        WorkflowResult result = service.executeWorkflow(wf, Map.of());

        Assertions.assertThat(result.isSuccess()).isTrue();
        Assertions.assertThat(result.getResult().toString()).contains("statusWas:404");
    }

    @Test
    @Order(6)
    public void httpAndJavaScriptInForEachLoop() {
        WorkflowExecutorService service = WorkflowTestHelper.programmaticService();

        // Fetch one item per ID in the loop
        ForEachData forEach = new ForEachData();
        forEach.setName("loop");
        forEach.setLoopOver("ids");
        forEach.setRunVar("currentId");
        forEach.setResultFrom("fetched");
        forEach.setAssignResultToVar("lastFetched");  // expose forEach result to parent scope

        HttpCallData fetch = new HttpCallData();
        fetch.setName("fetchUser");
        fetch.setUrl(base() + "/api/user?id=${currentId}");
        fetch.setMethod("GET");
        fetch.setAssignResultToVar("fetched");

        forEach.addSteps(fetch);

        WorkflowData wf = new WorkflowData();
        wf.setName("wf_foreach_http");
        wf.addSteps(forEach);
        wf.setResultFrom("lastFetched");

        WorkflowResult result = service.executeWorkflow(wf,
                Map.of("ids", java.util.List.of("1", "2", "3")));

        Assertions.assertThat(result.isSuccess()).isTrue();
        // Result is the last fetched user JSON (iteration over "3" last)
        Assertions.assertThat(result.getResult().toString()).contains("User-3");
    }

    @Test
    @Order(7)
    public void httpWorkflowLoadedFromJsonAndExecuted() throws Exception {
        WorkflowExecutorService service = WorkflowTestHelper.classpathService();

        // wf_http_and_code.json uses ${apiBase} — inject it via params
        WorkflowData wf =
                WorkflowTestHelper.serializer().readFromClasspath("/workflows/wf_http_and_code.json");

        WorkflowResult result = service.executeWorkflow(wf, Map.of("apiBase", base() + "/api"));

        Assertions.assertThat(result.isSuccess()).isTrue();
        Assertions.assertThat(result.getResult().toString())
                .startsWith("Fetched:")
                .contains("hello world");
    }
}
