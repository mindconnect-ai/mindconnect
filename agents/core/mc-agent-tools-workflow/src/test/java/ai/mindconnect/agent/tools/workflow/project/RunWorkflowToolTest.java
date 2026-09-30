package ai.mindconnect.agent.tools.workflow.project;

import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.service.workflows.ProjectWorkflowFiles;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.Tool;
import ai.mindconnect.agent.tool.ToolCallScope;
import ai.mindconnect.agent.tools.workflow.step.ToolInvoker;
import ai.mindconnect.agent.tools.workflow.step.ToolInvokers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code run_workflow} end to end on the real engine. {@code code_execute} is
 * played by the local python3 and node — the wrapper that carries variables
 * in and out is what is under test, not the container around it.
 */
class RunWorkflowToolTest {

    @TempDir
    Path project;

    private final List<String> toolCalls = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ToolInvokers.set(new ToolInvoker() {
            @Override
            public String call(String toolName, Map<String, Object> arguments) {
                toolCalls.add(toolName);
                if ("code_execute".equals(toolName)) {
                    return runLocally((String) arguments.get("language"), (String) arguments.get("code"));
                }
                return "echo " + arguments;
            }
        });
    }

    @AfterEach
    void tearDown() {
        ToolInvokers.clear();
    }

    private void workflow(String name, String yaml) throws IOException {
        Path dir = Files.createDirectories(project.resolve(ProjectWorkflowFiles.DIR));
        Files.writeString(dir.resolve(name + ".yaml"), yaml);
    }

    private Tool tool(List<String> tools, List<String> approval) {
        AgentTool binding = AgentTool.of(ProjectWorkflowFiles.TOOL, null, Map.of(
                ProjectWorkflowFiles.CALLER_TOOLS, tools,
                ProjectWorkflowFiles.APPROVAL_TOOLS, approval,
                ProjectWorkflowFiles.CALLER_AGENTS, List.of()));
        ToolCallScope scope = ToolCallScope.ofSession(UserId.of("u"), SessionId.of("s"), project.toString());
        return new RunWorkflowToolFactory().create(binding, scope);
    }

    private String run(Tool tool, String workflow, Map<String, Object> input) {
        return tool.execute(Map.of("workflow", workflow, "input", input));
    }

    @Test
    void describesTheProjectsWorkflows_includingOneThatDoesNotLoad() throws IOException {
        workflow("greet", """
                description: Greets someone
                input:
                  who: string
                steps:
                  - set: {greeting: "Hello ${who}"}
                result: greeting
                """);
        workflow("broken", """
                steps:
                  - call: elsewhere
                """);

        Tool tool = tool(List.of(), List.of());

        assertThat(tool.description())
                .contains("- greet: Greets someone Input: who (string, required).")
                .contains("- broken (does not load: a project workflow cannot use 'call'");
        assertThat(run(tool, "greet", Map.of("who", "Ada"))).isEqualTo("Hello Ada");
    }

    @Test
    void conditionsAreMiniScript() throws IOException {
        workflow("tier", """
                input:
                  score: integer
                steps:
                  - if: score >= 90
                    then:
                      - set: {tier: gold}
                    else:
                      - set: {tier: silver}
                result: tier
                """);
        Tool tool = tool(List.of(), List.of());
        assertThat(run(tool, "tier", Map.of("score", 93))).isEqualTo("gold");
        assertThat(run(tool, "tier", Map.of("score", 12))).isEqualTo("silver");
    }

    @Test
    void anInputThatLooksLikeAScript_staysAString() throws IOException {
        workflow("echo", """
                input:
                  text: string
                steps:
                  - set: {out: "${text}"}
                result: out
                """);
        String sneaky = "javascript: java.lang.System.exit(1)";
        assertThat(run(tool(List.of(), List.of()), "echo", Map.of("text", sneaky))).isEqualTo(sneaky);
    }

    @Test
    void anInputThatIsMiniScript_cannotReachAClass() throws IOException {
        workflow("echo", """
                input:
                  text: string
                steps:
                  - set: {out: "${text}"}
                result: out
                """);
        String result = run(tool(List.of(), List.of()), "echo",
                Map.of("text", "mini: \"x\".getClass().forName(\"java.lang.Runtime\")"));
        assertThat(result).startsWith("Error:").contains("Method not found");
    }

    @Test
    void toolSteps_reachOnlyTheCallersTools_andNoneThatAskForApproval() throws IOException {
        workflow("read", """
                steps:
                  - tool: file_read
                    args: {path: README.md}
                    as: text
                result: text
                """);
        workflow("shell", """
                steps:
                  - tool: bash
                    args: {command: ls}
                """);

        Tool tool = tool(List.of("file_read", "bash"), List.of("bash"));
        assertThat(run(tool, "read", Map.of())).isEqualTo("echo {path=README.md}");
        assertThat(run(tool, "shell", Map.of())).contains("asks for an approval");

        Tool narrow = tool(List.of("file_read"), List.of());
        assertThat(run(narrow, "shell", Map.of())).contains("not one of the calling agent's tools");
        assertThat(toolCalls).containsExactly("file_read");
    }

    @Test
    void codeSteps_needTheCallersCodeExecute() throws IOException {
        workflow("calc", """
                steps:
                  - code: "result = 1"
                """);
        assertThat(run(tool(List.of("file_read"), List.of()), "calc", Map.of()))
                .contains("tool 'code_execute' is not one of the calling agent's tools");
        assertThat(toolCalls).isEmpty();
    }

    @Test
    void pythonCode_seesTheVariables_andHandsNewOnesBack() throws IOException {
        Assumptions.assumeTrue(available("python3"), "python3 is not installed");
        workflow("calc", """
                input:
                  items: {type: array, items: {type: string}}
                steps:
                  - code: |
                      import json
                      upper = [i.upper() for i in items]
                      print("working")        # the program's own output does not get in the way
                      result = len(upper)
                    as: count
                  - set: {summary: "${count} items: ${upper}"}
                result: summary
                """);
        String result = run(tool(List.of("code_execute"), List.of()), "calc",
                Map.of("items", List.of("a", "b")));
        assertThat(result).isEqualTo("2 items: [A, B]");
    }

    @Test
    void nodeCode_seesTheVariables_andHandsNewOnesBack() throws IOException {
        Assumptions.assumeTrue(available("node"), "node is not installed");
        workflow("calc", """
                input:
                  name: string
                steps:
                  - code: |
                      var greeting = "Hi " + name.toUpperCase();
                      result = greeting.length;
                    language: node
                    as: size
                  - set: {out: "${greeting} (${size})"}
                result: out
                """);
        assertThat(run(tool(List.of("code_execute"), List.of()), "calc", Map.of("name", "ada")))
                .isEqualTo("Hi ADA (6)");
    }

    @Test
    void aFailingProgram_failsTheWorkflow_withWhatItPrinted() throws IOException {
        Assumptions.assumeTrue(available("python3"), "python3 is not installed");
        workflow("boom", """
                steps:
                  - code: raise ValueError("no sections")
                """);
        assertThat(run(tool(List.of("code_execute"), List.of()), "boom", Map.of()))
                .startsWith("Error:").contains("exited with 1").contains("no sections");
    }

    // -----------------------------------------------------------------------

    private static boolean available(String binary) {
        try {
            return new ProcessBuilder(binary, "--version").start().waitFor(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return false;
        }
    }

    /** What {@code code_execute} answers, with the program run by the local interpreter. */
    private static String runLocally(String language, String program) {
        List<String> command = "node".equals(language) ? List.of("node", "-") : List.of("python3", "-");
        try {
            Process process = new ProcessBuilder(command).start();
            process.getOutputStream().write(program.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor(30, TimeUnit.SECONDS);
            return "exit code: " + process.exitValue() + " (1 ms)\n--- stdout ---\n" + stdout
                    + (stderr.isBlank() ? "" : "--- stderr ---\n" + stderr);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
