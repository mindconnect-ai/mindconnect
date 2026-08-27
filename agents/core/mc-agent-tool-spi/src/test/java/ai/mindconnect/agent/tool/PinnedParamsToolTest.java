package ai.mindconnect.agent.tool;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent-pinned parameters must be invisible to the LLM (stripped from the
 * schema) and non-negotiable at execution time (written over whatever the
 * LLM passed).
 */
class PinnedParamsToolTest {

    /** Records the arguments it was executed with; offers language + code. */
    private static final class RecordingTool implements Tool {
        Map<String, Object> executedWith;

        @Override public String name() { return "code_execute"; }
        @Override public String description() { return "runs code"; }

        @Override public Map<String, Object> parametersSchema() {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", Map.of(
                    "language", Map.of("type", "string"),
                    "code", Map.of("type", "string")));
            schema.put("required", List.of("language", "code"));
            return schema;
        }

        @Override public String execute(Map<String, Object> arguments) {
            this.executedWith = arguments;
            return "ok";
        }
    }

    private static AgentTool toolWithOverrides(Map<String, Object> overrides) {
        return new AgentTool(null, null, "code_execute", null, overrides, true);
    }

    @Test
    void pinnedParamsAreStrippedFromSchemaAndForcedIntoArguments() {
        RecordingTool delegate = new RecordingTool();
        Tool tool = PinnedParamsTool.wrap(
                toolWithOverrides(Map.of("params", Map.of("language", "python"))), delegate);

        Map<String, Object> schema = tool.parametersSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");
        assertThat(properties).containsOnlyKeys("code");
        assertThat(required).containsExactly("code");

        // The LLM tries to sneak in node anyway — the pin wins.
        tool.execute(Map.of("language", "node", "code", "print(1)"));
        assertThat(delegate.executedWith)
                .containsEntry("language", "python")
                .containsEntry("code", "print(1)");
    }

    @Test
    void withoutParamsOverrideTheDelegateIsReturnedUnwrapped() {
        RecordingTool delegate = new RecordingTool();

        assertThat(PinnedParamsTool.wrap(toolWithOverrides(Map.of()), delegate)).isSameAs(delegate);
        assertThat(PinnedParamsTool.wrap(toolWithOverrides(Map.of("params", Map.of())), delegate))
                .isSameAs(delegate);
        assertThat(PinnedParamsTool.wrap(toolWithOverrides(Map.of("params", "not-a-map")), delegate))
                .isSameAs(delegate);
    }

    @Test
    void aliasExposesTheDelegateUnderTheAgentToolName() {
        RecordingTool delegate = new RecordingTool();
        AgentTool aliased = new AgentTool(null, null, "search_project_docs",
                "Searches the project knowledge base.",
                Map.of("tool", "vector_search", "params", Map.of("store", "projekt-kb")), true);

        assertThat(AliasTool.registryName(aliased)).isEqualTo("vector_search");
        Tool tool = PinnedParamsTool.wrap(aliased, AliasTool.wrap(aliased, delegate));

        assertThat(tool.name()).isEqualTo("search_project_docs");
        assertThat(tool.description()).isEqualTo("Searches the project knowledge base.");
        // Pin still enforces on the underlying parameter names:
        tool.execute(Map.of("code", "q"));
        assertThat(delegate.executedWith).containsEntry("store", "projekt-kb");
        // Without the alias override, the wrap is a no-op:
        AgentTool plain = new AgentTool(null, null, "code_execute", null, Map.of(), true);
        assertThat(AliasTool.wrap(plain, delegate)).isSameAs(delegate);
    }

    @Test
    void pinnedValueAppliesEvenWhenTheLlmOmitsTheParameter() {
        RecordingTool delegate = new RecordingTool();
        Tool tool = PinnedParamsTool.wrap(
                toolWithOverrides(Map.of("params", Map.of("language", "python"))), delegate);

        tool.execute(Map.of("code", "print(1)"));

        assertThat(delegate.executedWith).containsEntry("language", "python");
    }
}
