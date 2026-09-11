package ai.mindconnect.agent.tool;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The order every resolved tool passes through. Tested on
 * {@link SpiToolRegistry#decorate} directly: the chain is the contract, not
 * the ServiceLoader that happens to feed it.
 */
class SpiToolRegistryDecorationTest {


    @Test
    void an_agents_description_reaches_the_model_for_a_plain_tool() {
        // The point of this test: it did not, before. AliasTool carried the
        // description, and AliasTool only wraps aliased tools — so the field
        // looked effective on every other agent tool and did nothing.
        AgentTool bound = AgentTool.of("gmail_read_email",
                "Reads a mail by messageId — use the id from a prior search.");

        Tool tool = SpiToolRegistry.decorate(bound, delegate("gmail_read_email"));

        assertThat(tool.description())
                .isEqualTo("Reads a mail by messageId — use the id from a prior search.");
        assertThat(tool.name()).isEqualTo("gmail_read_email");
    }

    @Test
    void without_a_description_the_sources_own_text_stands() {
        Tool tool = SpiToolRegistry.decorate(AgentTool.of("glob"), delegate("glob"));

        assertThat(tool.description()).isEqualTo("description of glob");
    }

    @Test
    void a_blank_description_is_not_an_override() {
        AgentTool bound = AgentTool.of("glob", "   ");

        assertThat(SpiToolRegistry.decorate(bound, delegate("glob")).description())
                .isEqualTo("description of glob");
    }

    @Test
    void an_aliased_tool_gets_the_agents_name_and_the_agents_description() {
        AgentTool aliased = AgentTool.of("search_project_docs", "Searches the handbook.",
                Map.of(AliasTool.OVERRIDE_KEY, "vector_search"));

        Tool tool = SpiToolRegistry.decorate(aliased, delegate("vector_search"));

        assertThat(tool.name()).isEqualTo("search_project_docs");
        assertThat(tool.description()).isEqualTo("Searches the handbook.");
    }

    @Test
    void an_aliased_tool_without_a_description_keeps_the_targets() {
        AgentTool aliased = AgentTool.of("search_project_docs", null,
                Map.of(AliasTool.OVERRIDE_KEY, "vector_search"));

        Tool tool = SpiToolRegistry.decorate(aliased, delegate("vector_search"));

        assertThat(tool.name()).isEqualTo("search_project_docs");
        assertThat(tool.description()).isEqualTo("description of vector_search");
    }

    @Test
    void execution_passes_through_every_layer_untouched() {
        AgentTool bound = AgentTool.of("glob", "new words");

        assertThat(SpiToolRegistry.decorate(bound, delegate("glob")).execute(Map.of("path", ".")))
                .isEqualTo("ran glob");
    }

    @Test
    void a_pinned_parameter_still_leaves_the_schema() {
        // Pinning sits outermost and must keep working with a description
        // wrapper underneath it.
        AgentTool pinned = AgentTool.of("glob", "with a pin",
                Map.of(PinnedParamsTool.OVERRIDE_KEY, Map.of("path", "/projects")));

        Tool tool = SpiToolRegistry.decorate(pinned, delegate("glob"));

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) tool.parametersSchema().get("properties");
        assertThat(properties).doesNotContainKey("path").containsKey("pattern");
        assertThat(tool.description()).isEqualTo("with a pin");
    }

    private static Tool delegate(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "description of " + name; }
            @Override public Map<String, Object> parametersSchema() {
                Map<String, Object> properties = new LinkedHashMap<>();
                properties.put("path", new LinkedHashMap<>(Map.of("type", "string")));
                properties.put("pattern", new LinkedHashMap<>(Map.of("type", "string")));
                Map<String, Object> schema = new LinkedHashMap<>();
                schema.put("type", "object");
                schema.put("properties", properties);
                schema.put("required", List.of("path", "pattern"));
                return schema;
            }
            @Override public String execute(Map<String, Object> arguments) { return "ran " + name; }
        };
    }
}
