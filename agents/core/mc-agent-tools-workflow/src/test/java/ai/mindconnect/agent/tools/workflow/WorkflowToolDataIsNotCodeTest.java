package ai.mindconnect.agent.tools.workflow;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.MapToolEnvironment;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tools.workflow.step.ToolCallData;
import ai.mindconnect.agent.tools.workflow.step.ToolInvoker;
import ai.mindconnect.agent.tools.workflow.step.ToolInvokers;
import ai.mindconnect.schema.Schema;
import ai.mindconnect.workflow.domain.AssignVariablesData;
import ai.mindconnect.workflow.domain.VariableAssignment;
import ai.mindconnect.workflow.domain.WorkflowData;
import ai.mindconnect.workflow.persistence.file.FileWorkflowDataRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the model passes to a {@code workflow_<id>} tool, and what a tool or agent step
 * hands back, is data. A string that happens to look like a script expression
 * ({@code mini: …}, {@code js: …}) must arrive in the workflow as that string — evaluating
 * it would let a prompt injection run arbitrary code on the server. Expressions written
 * into the workflow definition itself keep being evaluated.
 */
class WorkflowToolDataIsNotCodeTest {

    /** Evaluates to {@code java.lang.String} — proof that a string was run as MiniScript. */
    private static final String PAYLOAD = "mini: \"x\".getClass().getName()";

    @TempDir
    Path dir;

    private FileWorkflowDataRepository repository;
    private WorkflowToolProvider provider;

    @BeforeEach
    void setUp() {
        repository = new FileWorkflowDataRepository(dir, "test");
        provider = new WorkflowToolProvider();
        provider.bind(MapToolEnvironment.builder()
                .string("dataBaseDir", dir.toString())
                .service(Namespace.class, new Namespace("test"))
                .build());
    }

    @AfterEach
    void tearDown() {
        ToolInvokers.clear();
    }

    private Tool tool(String workflowId) {
        return provider.create("workflow_" + workflowId, AgentTool.of("workflow_" + workflowId),
                new ToolCallScope(null, null, null)).orElseThrow();
    }

    /** A workflow whose result is its input {@code text}, untouched. */
    private void saveEcho() {
        WorkflowData wf = new WorkflowData();
        wf.setName("echo");
        wf.setResultFrom("text");
        wf.setParams(Schema.object().prop("text", Schema.string()).require("text"));
        repository.save("echo", wf);
    }

    @Test
    void anArgumentThatLooksLikeAScriptStaysAString() {
        saveEcho();

        assertThat(tool("echo").execute(Map.of("text", PAYLOAD))).isEqualTo(PAYLOAD);
    }

    @Test
    void aToolResultThatLooksLikeAScriptStaysAString() {
        ToolCallData fetch = new ToolCallData();
        fetch.setName("fetch");
        fetch.setTool("web_fetch");
        fetch.setArguments("{}");
        fetch.setAssignResultToVar("page");
        WorkflowData wf = new WorkflowData();
        wf.setName("fetching");
        wf.setResultFrom("page");
        wf.addSteps(fetch);
        repository.save("fetching", wf);

        // A web page, a mail, another agent's answer — whatever the tool returns.
        ToolInvokers.set((tool, args) -> PAYLOAD);

        assertThat(tool("fetching").execute(Map.of())).isEqualTo(PAYLOAD);
    }

    @Test
    void anExpressionInTheDefinitionIsStillEvaluated() {
        AssignVariablesData measure = new AssignVariablesData();
        measure.setName("measure");
        measure.getVariableAssignments().add(new VariableAssignment("size", "mini: text.length()"));
        WorkflowData wf = new WorkflowData();
        wf.setName("measuring");
        wf.setResultFrom("size");
        wf.addSteps(measure);
        wf.setParams(Schema.object().prop("text", Schema.string()).require("text"));
        repository.save("measuring", wf);

        assertThat(tool("measuring").execute(Map.of("text", PAYLOAD)))
                .isEqualTo(String.valueOf(PAYLOAD.length()));
    }

    /**
     * {@code ${…}} inside a script expression used to be pasted into the script's text
     * before it ran, so a value closing the string literal became code. A script reads
     * variables by name; {@code ${…}} is substitution for plain text only.
     */
    @Test
    void aPlaceholderInsideAScriptDoesNotPasteTheValueIntoTheCode() {
        AssignVariablesData quote = new AssignVariablesData();
        quote.setName("quote");
        quote.getVariableAssignments().add(new VariableAssignment("quoted", "mini: \"${text}\""));
        WorkflowData wf = new WorkflowData();
        wf.setName("quoting");
        wf.setResultFrom("quoted");
        wf.addSteps(quote);
        wf.setParams(Schema.object().prop("text", Schema.string()).require("text"));
        repository.save("quoting", wf);

        String breakout = "\" + \"x\".getClass().getName() + \"";

        assertThat(tool("quoting").execute(Map.of("text", breakout)))
                .doesNotContain("java.lang.String");
    }
}
