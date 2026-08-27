package ai.mindconnect.workflow.http;

import ai.mindconnect.workflow.domain.AssignVariablesData;
import ai.mindconnect.workflow.domain.HttpCallData;
import ai.mindconnect.workflow.domain.VariableAssignment;
import ai.mindconnect.workflow.domain.WorkflowData;
import ai.mindconnect.workflow.execution.*;
import com.sun.net.httpserver.HttpServer;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Integration tests for {@link HttpCallStep} using an embedded JDK {@link HttpServer}.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class HttpCallStepTest {

    private static HttpServer server;
    private static int port;

    @BeforeAll
    static void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();

        // GET /hello  → 200 {"message":"hello world"}
        server.createContext("/hello", exchange -> {
            byte[] body = "{\"message\":\"hello world\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });

        // GET /echo?name=X  → 200 {"name":"X"}  (reads query param from URL)
        server.createContext("/echo", exchange -> {
            String query = exchange.getRequestURI().getQuery(); // e.g. "name=World"
            String name = "unknown";
            if (query != null) {
                for (String part : query.split("&")) {
                    if (part.startsWith("name=")) name = part.substring(5);
                }
            }
            byte[] resp = ("{\"name\":\"" + name + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.getResponseBody().close();
        });

        // POST /submit  → 201 {"status":"created"}
        server.createContext("/submit", exchange -> {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            byte[] resp = ("{\"status\":\"created\",\"received\":" + requestBody.length + "}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(201, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.getResponseBody().close();
        });

        // GET /error  → 500 {"error":"internal"}
        server.createContext("/error", exchange -> {
            byte[] resp = "{\"error\":\"internal\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.getResponseBody().close();
        });

        // GET /notfound  → 404
        server.createContext("/notfound", exchange -> {
            byte[] resp = "Not Found".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.getResponseBody().close();
        });

        // GET /status  → 200 with custom header
        server.createContext("/status", exchange -> {
            byte[] resp = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("X-Custom-Header", "workflow-test");
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.getResponseBody().close();
        });

        server.start();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) server.stop(0);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private WorkflowExecutorService buildService() {
        // DefaultWorkflowContextFactory already registers HttpCallStep via StepInstanceFactory
        WorkflowContextFactory ctxFactory = new DefaultWorkflowContextFactory();
        return new WorkflowExecutorService(ctxFactory);
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    @Order(1)
    public void simpleGetReturnsBody() {
        WorkflowExecutorService service = buildService();

        HttpCallData step = new HttpCallData();
        step.setName("getHello");
        step.setUrl(baseUrl() + "/hello");
        step.setMethod("GET");
        step.setAssignResultToVar("response");

        WorkflowData wf = new WorkflowData();
        wf.setName("wf_get");
        wf.addSteps(step);
        wf.setResultFrom("response");

        WorkflowResult result = service.executeWorkflow(wf, Map.of());

        Assertions.assertThat(result.isSuccess()).isTrue();
        Assertions.assertThat(result.getResult().toString()).contains("hello world");
    }

    @Test
    @Order(2)
    public void urlWithVariableSubstitution() {
        WorkflowExecutorService service = buildService();

        HttpCallData step = new HttpCallData();
        step.setName("echo");
        step.setUrl(baseUrl() + "/echo?name=${name}");
        step.setMethod("GET");
        step.setAssignResultToVar("response");

        WorkflowData wf = new WorkflowData();
        wf.setName("wf_echo");
        wf.addSteps(step);
        wf.setResultFrom("response");

        WorkflowResult result = service.executeWorkflow(wf, Map.of("name", "Jackson"));

        Assertions.assertThat(result.isSuccess()).isTrue();
        Assertions.assertThat(result.getResult().toString()).contains("Jackson");
    }

    @Test
    @Order(3)
    public void postWithBody() {
        WorkflowExecutorService service = buildService();

        HttpCallData step = new HttpCallData();
        step.setName("submit");
        step.setUrl(baseUrl() + "/submit");
        step.setMethod("POST");
        step.setBody("{\"key\":\"${value}\"}");
        step.setAssignResultToVar("response");

        WorkflowData wf = new WorkflowData();
        wf.setName("wf_post");
        wf.addSteps(step);
        wf.setResultFrom("response");

        WorkflowResult result = service.executeWorkflow(wf, Map.of("value", "test123"));

        Assertions.assertThat(result.isSuccess()).isTrue();
        Assertions.assertThat(result.getResult().toString()).contains("created");
    }

    @Test
    @Order(4)
    public void serverErrorThrowsHttpStepException() {
        WorkflowExecutorService service = buildService();

        HttpCallData step = new HttpCallData();
        step.setName("err");
        step.setUrl(baseUrl() + "/error");
        step.setMethod("GET");
        step.setFailOnError(true);

        WorkflowData wf = new WorkflowData();
        wf.setName("wf_err");
        wf.addSteps(step);

        WorkflowResult result = service.executeWorkflow(wf, Map.of());

        Assertions.assertThat(result.isError()).isTrue();
        Assertions.assertThat(result.getError()).isInstanceOf(StepExecutionException.class);

        StepExecutionException stepEx = (StepExecutionException) result.getError();
        Assertions.assertThat(stepEx.getCausingException()).isInstanceOf(HttpStepException.class);

        HttpStepException cause = (HttpStepException) stepEx.getCausingException();
        Assertions.assertThat(cause.getStatusCode()).isEqualTo(500);
    }

    @Test
    @Order(5)
    public void failOnErrorFalseReturnsBodyOnNon2xx() {
        WorkflowExecutorService service = buildService();

        HttpCallData step = new HttpCallData();
        step.setName("notfound");
        step.setUrl(baseUrl() + "/notfound");
        step.setMethod("GET");
        step.setFailOnError(false);
        step.setAssignResultToVar("body");

        WorkflowData wf = new WorkflowData();
        wf.setName("wf_notfound");
        wf.addSteps(step);
        wf.setResultFrom("body");

        WorkflowResult result = service.executeWorkflow(wf, Map.of());

        Assertions.assertThat(result.isSuccess()).isTrue();
        Assertions.assertThat(result.getResult().toString()).contains("Not Found");
    }

    @Test
    @Order(6)
    public void statusCodeStoredInVariable() {
        WorkflowExecutorService service = buildService();

        HttpCallData step = new HttpCallData();
        step.setName("getStatus");
        step.setUrl(baseUrl() + "/hello");
        step.setMethod("GET");
        step.setStatusCodeVar("httpStatus");
        step.setAssignResultToVar("body");

        WorkflowData wf = new WorkflowData();
        wf.setName("wf_status");
        wf.addSteps(step);
        wf.setResultFrom("httpStatus");

        WorkflowResult result = service.executeWorkflow(wf, Map.of());

        Assertions.assertThat(result.isSuccess()).isTrue();
        Assertions.assertThat(result.getResult()).isEqualTo(200);
    }

    @Test
    @Order(7)
    public void responseHeadersStoredInVariable() {
        WorkflowExecutorService service = buildService();

        HttpCallData step = new HttpCallData();
        step.setName("getHeaders");
        step.setUrl(baseUrl() + "/status");
        step.setMethod("GET");
        step.setResponseHeadersVar("respHeaders");
        step.setAssignResultToVar("body");

        WorkflowData wf = new WorkflowData();
        wf.setName("wf_headers");
        wf.addSteps(step);
        wf.setResultFrom("respHeaders");

        WorkflowResult result = service.executeWorkflow(wf, Map.of());

        Assertions.assertThat(result.isSuccess()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, String> headers = (Map<String, String>) result.getResult();
        Assertions.assertThat(headers).containsKey("x-custom-header");
        Assertions.assertThat(headers.get("x-custom-header")).contains("workflow-test");
    }

    @Test
    @Order(8)
    public void httpStepInMultiStepWorkflow() {
        WorkflowExecutorService service = buildService();

        // Step 1: HTTP call
        HttpCallData httpStep = new HttpCallData();
        httpStep.setName("fetch");
        httpStep.setUrl(baseUrl() + "/hello");
        httpStep.setMethod("GET");
        httpStep.setAssignResultToVar("rawResponse");

        // Step 2: assign a transformed variable
        AssignVariablesData assignStep = new AssignVariablesData();
        assignStep.setName("tag");
        assignStep.getVariableAssignments()
                .add(new VariableAssignment("tagged", "TAGGED:${rawResponse}"));
        assignStep.setAssignResultToVar("tagged");

        WorkflowData wf = new WorkflowData();
        wf.setName("wf_multi");
        wf.addSteps(httpStep, assignStep);
        wf.setResultFrom("tagged");

        WorkflowResult result = service.executeWorkflow(wf, Map.of());

        Assertions.assertThat(result.isSuccess()).isTrue();
        Assertions.assertThat(result.getResult().toString())
                .startsWith("TAGGED:")
                .contains("hello world");
    }

    @Test
    @Order(9)
    public void customRequestHeader() {
        WorkflowExecutorService service = buildService();

        HttpCallData step = new HttpCallData();
        step.setName("withHeader");
        step.setUrl(baseUrl() + "/hello");
        step.setMethod("GET");
        step.getHeaders().add(new VariableAssignment("Authorization", "Bearer ${token}"));
        step.setAssignResultToVar("response");

        WorkflowData wf = new WorkflowData();
        wf.setName("wf_header");
        wf.addSteps(step);
        wf.setResultFrom("response");

        // Server doesn't validate the token, just checking the step doesn't blow up
        WorkflowResult result = service.executeWorkflow(wf, Map.of("token", "my-secret"));

        Assertions.assertThat(result.isSuccess()).isTrue();
        Assertions.assertThat(result.getResult().toString()).contains("hello world");
    }
}
