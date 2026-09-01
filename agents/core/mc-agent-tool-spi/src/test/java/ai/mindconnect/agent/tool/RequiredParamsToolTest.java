package ai.mindconnect.agent.tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RequiredParamsToolTest {

    private static final Tool DELEGATE = new Tool() {
        @Override public String name() { return "web_read"; }
        @Override public String description() { return "reads a page"; }
        @Override public Map<String, Object> parametersSchema() {
            return Map.of(
                    "type", "object",
                    "properties", Map.of("url", Map.of("type", "string"),
                                         "query", Map.of("type", "string")),
                    "required", List.of("url"));
        }
        @Override public String execute(Map<String, Object> arguments) {
            return "read " + arguments.get("url") + " for " + arguments.get("query");
        }
    };

    private static AgentTool binding(Map<String, Object> overrides) {
        return new AgentTool(UUID.randomUUID(), UUID.randomUUID(), "web_read", null,
                overrides, true, false, false, null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void adds_the_name_to_the_schema_keeping_what_was_already_required() {
        Tool tool = RequiredParamsTool.wrap(binding(Map.of("requiredParams", List.of("query"))), DELEGATE);
        assertThat((List<String>) tool.parametersSchema().get("required"))
                .containsExactly("url", "query");
    }

    @Test
    void refuses_a_call_that_omits_it_without_running_the_tool() {
        Tool tool = RequiredParamsTool.wrap(binding(Map.of("requiredParams", List.of("query"))), DELEGATE);
        assertThat(tool.execute(Map.of("url", "https://example.com")))
                .startsWith("Error:")
                .contains("query");
    }

    @Test
    void treats_a_blank_value_as_missing() {
        Tool tool = RequiredParamsTool.wrap(binding(Map.of("requiredParams", List.of("query"))), DELEGATE);
        assertThat(tool.execute(Map.of("url", "https://example.com", "query", "   ")))
                .startsWith("Error:");
    }

    @Test
    void passes_a_complete_call_straight_through() {
        Tool tool = RequiredParamsTool.wrap(binding(Map.of("requiredParams", List.of("query"))), DELEGATE);
        assertThat(tool.execute(Map.of("url", "https://example.com", "query", "price")))
                .isEqualTo("read https://example.com for price");
    }

    @Test
    @SuppressWarnings("unchecked")
    void ignores_a_name_the_tool_does_not_offer() {
        Tool tool = RequiredParamsTool.wrap(binding(Map.of("requiredParams", List.of("nonsense"))), DELEGATE);
        assertThat((List<String>) tool.parametersSchema().get("required")).containsExactly("url");
    }

    @Test
    void is_the_identity_without_the_override() {
        assertThat(RequiredParamsTool.wrap(binding(Map.of()), DELEGATE)).isSameAs(DELEGATE);
        assertThat(RequiredParamsTool.wrap(binding(Map.of("requiredParams", List.of())), DELEGATE))
                .isSameAs(DELEGATE);
        assertThat(RequiredParamsTool.wrap(binding(Map.of("requiredParams", "not-a-list")), DELEGATE))
                .isSameAs(DELEGATE);
    }

    @Test
    @SuppressWarnings("unchecked")
    void a_pinned_parameter_is_not_also_demanded_from_the_model() {
        // Pinning strips the parameter from the schema; requiring it on top
        // would ask the model for something it is no longer offered.
        AgentTool both = binding(Map.of("requiredParams", List.of("query"),
                                        "params", Map.of("query", "fixed")));
        Tool tool = PinnedParamsTool.wrap(both, RequiredParamsTool.wrap(both, DELEGATE));
        assertThat((List<String>) tool.parametersSchema().get("required")).containsExactly("url");
        assertThat(tool.execute(Map.of("url", "https://example.com")))
                .isEqualTo("read https://example.com for fixed");
    }
}
