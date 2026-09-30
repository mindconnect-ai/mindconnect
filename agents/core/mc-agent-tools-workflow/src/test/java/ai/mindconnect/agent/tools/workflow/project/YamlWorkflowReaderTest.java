package ai.mindconnect.agent.tools.workflow.project;

import ai.mindconnect.agent.tools.workflow.step.AgentCallData;
import ai.mindconnect.agent.tools.workflow.step.SandboxCodeData;
import ai.mindconnect.agent.tools.workflow.step.ToolCallData;
import ai.mindconnect.workflow.domain.AssignVariablesData;
import ai.mindconnect.workflow.domain.ForEachData;
import ai.mindconnect.workflow.domain.IfData;
import ai.mindconnect.workflow.domain.WorkflowData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YamlWorkflowReaderTest {

    private static WorkflowData parse(String yaml) throws Exception {
        return YamlWorkflowReader.parse("wf", yaml, null).workflow();
    }

    @Test
    void readsEveryStepType() throws Exception {
        YamlWorkflowReader.Read read = YamlWorkflowReader.parse("summarise", """
                description: Summarises sections
                input:
                  wordFile: string
                  targetDir: {type: string, default: out, description: Where to write}
                result: summary
                steps:
                  - tool: document_sections
                    args: {path: "${wordFile}"}
                    as: sectionsJson
                  - code: |
                      sections = [1, 2]
                  - foreach: sections
                    item: section
                    join: ", "
                    as: summary
                    steps:
                      - agent: Summarizer
                        message: "Summarise ${section}"
                  - if: len(sections) > 1
                    then:
                      - set: {note: many, count: 2}
                    else:
                      - set: {note: few}
                """, null);

        WorkflowData wf = read.workflow();
        assertThat(read.description()).isEqualTo("Summarises sections");
        assertThat(wf.getName()).isEqualTo("summarise");
        assertThat(wf.getResultFrom()).isEqualTo("summary");
        assertThat(wf.getParams().getRequired()).containsExactly("wordFile");
        assertThat(wf.getParams().getProperties().get("targetDir").getDefaultValue()).isEqualTo("out");

        assertThat(wf.getSteps()).hasSize(4);
        ToolCallData tool = (ToolCallData) wf.getSteps().get(0);
        assertThat(tool.getTool()).isEqualTo("document_sections");
        assertThat(tool.getArgumentValues()).isEqualTo(java.util.Map.of("path", "${wordFile}"));
        assertThat(tool.getArguments()).isNull();
        assertThat(tool.getAssignResultToVar()).isEqualTo("sectionsJson");

        SandboxCodeData code = (SandboxCodeData) wf.getSteps().get(1);
        assertThat(code.getLanguage()).isEqualTo("python");
        assertThat(code.getName()).isEqualTo("code-1");

        ForEachData loop = (ForEachData) wf.getSteps().get(2);
        assertThat(loop.getLoopOver()).isEqualTo("sections");
        assertThat(loop.getRunVar()).isEqualTo("section");
        assertThat(loop.isJoinResults()).isTrue();
        assertThat(loop.getSteps().get(0)).isInstanceOf(AgentCallData.class);

        IfData branch = (IfData) wf.getSteps().get(3);
        assertThat(branch.getConditions()[0].getCondition()).isEqualTo("mini: len(sections) > 1");
        AssignVariablesData set = (AssignVariablesData) branch.getConditions()[0].getThenBlock().getSteps().get(0);
        assertThat(set.getVariableAssignments()).extracting(a -> a.getExpressionOrVarName())
                .containsExactly("many", "json: 2");
        assertThat(branch.getElseBlock().getSteps()).hasSize(1);
    }

    @Test
    void codeLanguage_acceptsTheUsualNames() throws Exception {
        WorkflowData wf = parse("""
                steps:
                  - code: "x = 1"
                    language: javascript
                """);
        assertThat(((SandboxCodeData) wf.getSteps().get(0)).getLanguage()).isEqualTo("node");
        assertThatThrownBy(() -> parse("""
                steps:
                  - code: "x = 1"
                    language: groovy
                """)).hasMessageContaining("python or node");
    }

    @Test
    void stepsThatReachPastTheCaller_areRejected() {
        assertThatThrownBy(() -> parse("""
                steps:
                  - call: other
                """)).hasMessageContaining("cannot use 'call'");
        assertThatThrownBy(() -> parse("""
                steps:
                  - http: https://example.com
                """)).hasMessageContaining("cannot use 'http'");
    }

    @Test
    void scriptExpressionsOtherThanMini_areRejectedWhereAnExpressionIsMeant() {
        assertThatThrownBy(() -> parse("""
                steps:
                  - set: {x: "javascript: java.lang.System.exit(1)"}
                """)).hasMessageContaining("'javascript:' expressions are not available");
        assertThatThrownBy(() -> parse("""
                steps:
                  - if: "groovy: true"
                    then: []
                """)).hasMessageContaining("'groovy:'");
    }

    @Test
    void textThatStartsLikeALanguage_isJustText() throws Exception {
        WorkflowData wf = parse("""
                steps:
                  - agent: Tutor
                    message: "Python: explain list comprehensions"
                  - tool: web_search
                    args: {query: "JavaScript: closures"}
                  - set: {title: "Python: a primer"}
                """);
        assertThat(((AgentCallData) wf.getSteps().get(0)).getMessage()).startsWith("Python:");
        assertThat(wf.getSteps()).hasSize(3);
    }

    @Test
    void typosAndAmbiguity_areNamed() {
        assertThatThrownBy(() -> parse("""
                steps:
                  - tool: file_read
                    arg: {path: x}
                """)).hasMessageContaining("unknown key 'arg'");
        assertThatThrownBy(() -> parse("""
                steps:
                  - tool: file_read
                    agent: Helper
                """)).hasMessageContaining("several");
        assertThatThrownBy(() -> parse("steps: []")).hasMessageContaining("no steps");
    }

    @Test
    void codeFile_isReadFromBesideTheWorkflow_andNotFromElsewhere(@TempDir Path dir) throws Exception {
        Path wfDir = Files.createDirectories(dir.resolve("report"));
        Files.writeString(wfDir.resolve("transform.js"), "var y = 2;");
        Files.writeString(dir.resolve("secret.txt"), "nope");

        WorkflowData wf = YamlWorkflowReader.parse("report", """
                steps:
                  - code: {file: transform.js}
                """, wfDir).workflow();
        SandboxCodeData code = (SandboxCodeData) wf.getSteps().get(0);
        assertThat(code.getCode()).isEqualTo("var y = 2;");
        assertThat(code.getLanguage()).isEqualTo("node");

        assertThatThrownBy(() -> YamlWorkflowReader.parse("report", """
                steps:
                  - code: {file: ../secret.txt}
                """, wfDir)).hasMessageContaining("outside the workflow's directory");

        // A symlink beside the workflow counts where it points.
        Files.createSymbolicLink(wfDir.resolve("leak.py"), dir.resolve("secret.txt"));
        assertThatThrownBy(() -> YamlWorkflowReader.parse("report", """
                steps:
                  - code: {file: leak.py}
                """, wfDir)).hasMessageContaining("outside the workflow's directory");
    }
}
