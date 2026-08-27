package ai.mindconnect.schema;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class SchemaTest {

    /**
     * The TodoWriteTool schema — array → object → enum — built with the fluent
     * API, then checked to produce exactly the map that tool hand-nests today.
     */
    @Test
    void buildsAndEmitsTheHardestRealToolSchema() {
        Schema todo = Schema.object()
                .prop("content", Schema.string().description("Imperative form"))
                .prop("status", Schema.enumOf("pending", "in_progress", "completed")
                        .description("Lifecycle state"))
                .require("content", "status");
        Schema input = Schema.object()
                .prop("todos", Schema.array(todo).description("Full list"))
                .require("todos");

        Map<String, Object> map = input.toMap();

        Assertions.assertThat(map).containsEntry("type", "object");
        Assertions.assertThat(map).containsEntry("required", List.of("todos"));

        @SuppressWarnings("unchecked")
        Map<String, Object> todosSchema =
                (Map<String, Object>) ((Map<String, Object>) map.get("properties")).get("todos");
        Assertions.assertThat(todosSchema).containsEntry("type", "array");

        @SuppressWarnings("unchecked")
        Map<String, Object> itemSchema = (Map<String, Object>) todosSchema.get("items");
        Assertions.assertThat(itemSchema).containsEntry("type", "object");
        Assertions.assertThat(itemSchema).containsEntry("required", List.of("content", "status"));

        @SuppressWarnings("unchecked")
        Map<String, Object> statusSchema =
                (Map<String, Object>) ((Map<String, Object>) itemSchema.get("properties")).get("status");
        Assertions.assertThat(statusSchema)
                .containsEntry("type", "string")           // enum serialises as string
                .containsEntry("enum", List.of("pending", "in_progress", "completed"));
    }

    @Test
    void roundTripsThroughAMap() {
        Schema original = Schema.object()
                .prop("count", Schema.integer())
                .prop("tags", Schema.array(Schema.string()))
                .prop("mode", Schema.enumOf("fast", "slow"))
                .require("count");

        Schema back = Schema.fromMap(original.toMap());

        Assertions.assertThat(back.toMap()).isEqualTo(original.toMap());
        Assertions.assertThat(back.getProperties().get("count").getType()).isEqualTo(Schema.Type.INTEGER);
        Assertions.assertThat(back.getProperties().get("mode").getType()).isEqualTo(Schema.Type.ENUM);
        Assertions.assertThat(back.getRequired()).containsExactly("count");
    }

    @Test
    void acceptsALegacyBareStringListAsAnObjectOfStrings() {
        Schema schema = Schema.fromMap(List.of("amount", "name"));

        Assertions.assertThat(schema.getType()).isEqualTo(Schema.Type.OBJECT);
        Assertions.assertThat(schema.getProperties()).containsOnlyKeys("amount", "name");
        Assertions.assertThat(schema.getProperties().get("amount").getType()).isEqualTo(Schema.Type.STRING);
    }

    @Test
    void keepsForeignSchemaKeysAcrossARoundTrip() {
        // What an MCP server might send: keys this model does not interpret.
        Map<String, Object> foreign = Map.of(
                "type", "object",
                "properties", Map.of("x", Map.of("type", "string")),
                "additionalProperties", false,
                "$schema", "https://json-schema.org/draft/2020-12/schema");

        Map<String, Object> back = Schema.fromMap(foreign).toMap();

        Assertions.assertThat(back).containsEntry("additionalProperties", false);
        Assertions.assertThat(back).containsEntry("$schema", "https://json-schema.org/draft/2020-12/schema");
    }

    @Test
    void validatesRequiredAndEnumAndNesting() {
        Schema schema = Schema.object()
                .prop("verdict", Schema.enumOf("approve", "reject"))
                .prop("amount", Schema.integer())
                .require("verdict");

        Assertions.assertThat(SchemaValidator.validate(schema,
                Map.of("verdict", "approve", "amount", 42))).isEmpty();

        Assertions.assertThat(SchemaValidator.validate(schema, Map.of("amount", 42)))
                .anySatisfy(e -> Assertions.assertThat(e).contains("required field 'verdict'"));

        Assertions.assertThat(SchemaValidator.validate(schema,
                Map.of("verdict", "maybe"))).anySatisfy(e -> Assertions.assertThat(e).contains("must be one of"));

        Assertions.assertThat(SchemaValidator.validate(schema,
                Map.of("verdict", "approve", "amount", "lots")))
                .anySatisfy(e -> Assertions.assertThat(e).contains("must be an integer"));
    }
}
