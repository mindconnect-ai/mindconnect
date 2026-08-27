package ai.mindconnect.agent.tools.workflow;

import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tool.MapToolEnvironment;
import ai.mindconnect.schema.Schema;
import ai.mindconnect.workflow.domain.AssignVariablesData;
import ai.mindconnect.workflow.domain.HaltData;
import ai.mindconnect.workflow.domain.VariableAssignment;
import ai.mindconnect.workflow.domain.WorkflowData;
import ai.mindconnect.workflow.persistence.file.FileWorkflowDataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The provider's contract: one tool per persisted workflow ({@code workflow_<id>}), the LLM
 * parameter schema is the workflow's input schema, arguments are validated against it, and
 * the three engine outcomes (success / error via bad args / halt) come back as readable text.
 */
class WorkflowToolProviderTest {

    @TempDir
    Path dir;

    private FileWorkflowDataRepository repository;
    private WorkflowToolProvider provider;

    /** The "hello" seed in miniature: one assign step, result from the assigned variable. */
    private static WorkflowData greeting() {
        AssignVariablesData greet = new AssignVariablesData();
        greet.setName("greet");
        greet.setAssignResultToVar("greeting");
        greet.getVariableAssignments().add(new VariableAssignment("greeting", "Hello ${name}!"));

        WorkflowData wf = new WorkflowData();
        wf.setName("greeting");
        wf.setResultFrom("greeting");
        wf.addSteps(greet);
        wf.setParams(Schema.object()
                .prop("name", Schema.string().description("Who to greet"))
                .require("name"));
        return wf;
    }

    private static WorkflowData halting() {
        HaltData halt = new HaltData();
        halt.setName("wait-for-approval");
        halt.setResumeParams(Schema.object().prop("decision", Schema.enumOf("approve", "reject")));

        WorkflowData wf = new WorkflowData();
        wf.setName("halting");
        wf.addSteps(halt);
        return wf;
    }

    @BeforeEach
    void setUp() {
        repository = new FileWorkflowDataRepository(dir);
        repository.save("greeting", greeting());
        repository.save("halting", halting());

        provider = new WorkflowToolProvider();
        provider.bind(MapToolEnvironment.builder().string("workflowDir", dir.toString()).build());
    }

    private Tool tool(String name) {
        // Mirrors the tool catalog's probe: null userId and sessionId must be tolerated.
        Optional<Tool> tool = provider.create(name,
                AgentTool.of(UUID.randomUUID(), name), new ToolCallScope(null, null, null, null));
        assertThat(tool).isPresent();
        return tool.get();
    }

    @Test
    void offersOneToolPerPersistedWorkflow() {
        assertThat(provider.isAvailable()).isTrue();
        assertThat(provider.toolNames()).containsExactlyInAnyOrder("workflow_greeting", "workflow_halting");
    }

    @Test
    void workflowsCreatedAndDeletedAfterBindAreReflectedLive() {
        WorkflowData wf = greeting();
        wf.setName("later");
        repository.save("later", wf);
        assertThat(provider.toolNames()).contains("workflow_later");
        assertThat(tool("workflow_later").execute(Map.of("name", "Ada"))).isEqualTo("Hello Ada!");

        repository.delete("later");
        assertThat(provider.toolNames()).doesNotContain("workflow_later");
    }

    @Test
    void parametersSchemaIsTheWorkflowInputSchema() {
        Map<String, Object> schema = tool("workflow_greeting").parametersSchema();

        assertThat(schema).containsEntry("type", "object");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertThat(properties).containsKey("name");
        assertThat(schema.get("required")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .containsExactly("name");
    }

    @Test
    void executesTheWorkflowWithLlmArguments() {
        String result = tool("workflow_greeting").execute(Map.of("name", "David"));
        assertThat(result).isEqualTo("Hello David!");
    }

    @Test
    void rejectsArgumentsThatViolateTheSchema() {
        String result = tool("workflow_greeting").execute(Map.of());
        assertThat(result).startsWith("Invalid arguments for workflow 'greeting'");
        assertThat(result).contains("name");
    }

    @Test
    void haltReportsWhatTheWorkflowIsWaitingFor() {
        String result = tool("workflow_halting").execute(Map.of());
        assertThat(result).contains("halted");
        assertThat(result).contains("decision");
    }

    @Test
    void editedDefinitionAppliesOnNextExecutionWithoutRebinding() {
        Tool tool = tool("workflow_greeting");
        assertThat(tool.execute(Map.of("name", "David"))).isEqualTo("Hello David!");

        WorkflowData edited = greeting();
        edited.getSteps().clear();
        AssignVariablesData greet = new AssignVariablesData();
        greet.setName("greet");
        greet.setAssignResultToVar("greeting");
        greet.getVariableAssignments().add(new VariableAssignment("greeting", "Servus ${name}!"));
        edited.addSteps(greet);
        repository.save("greeting", edited);

        assertThat(tool.execute(Map.of("name", "David"))).isEqualTo("Servus David!");
    }

    @Test
    void unknownWorkflowYieldsNoTool() {
        assertThat(provider.create("workflow_nope",
                AgentTool.of(UUID.randomUUID(), "workflow_nope"),
                new ToolCallScope(null, null, null, null))).isEmpty();
        assertThat(provider.create("not_a_workflow_tool",
                AgentTool.of(UUID.randomUUID(), "not_a_workflow_tool"),
                new ToolCallScope(null, null, null, null))).isEmpty();
    }
}
