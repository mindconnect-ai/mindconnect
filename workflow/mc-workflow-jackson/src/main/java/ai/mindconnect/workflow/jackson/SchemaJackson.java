package ai.mindconnect.workflow.jackson;

import ai.mindconnect.schema.Schema;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;

import java.io.IOException;
import java.util.Map;

/**
 * Reads and writes {@link Schema} as canonical JSON Schema.
 *
 * <p>{@code Schema} is a Jackson-free type in {@code mc-schema}; all the Jackson
 * wiring lives here, and it delegates to the model's own {@link Schema#toMap()}
 * / {@link Schema#fromMap} bridge. The effect is that a stored workflow keeps
 * its parameters as ordinary JSON Schema — the same shape an LLM tool or MCP
 * uses — rather than as a dump of the Java object's fields. And because
 * {@code fromMap} accepts a bare list of names, workflow files written before
 * params were typed still load.
 */
public final class SchemaJackson {

    private SchemaJackson() {}

    public static SimpleModule module() {
        SimpleModule module = new SimpleModule();
        module.addSerializer(Schema.class, new Serializer());
        module.addDeserializer(Schema.class, new Deserializer());
        return module;
    }

    static final class Serializer extends JsonSerializer<Schema> {
        @Override
        public void serialize(Schema schema, JsonGenerator gen, SerializerProvider provider)
                throws IOException {
            gen.writeObject(schema.toMap());
        }
    }

    static final class Deserializer extends JsonDeserializer<Schema> {
        @Override
        public Schema deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            // Read the node as plain maps/lists, then let the model interpret it.
            // A JSON object -> canonical schema; a JSON array of names -> legacy.
            Object raw = p.readValueAs(Object.class);
            return Schema.fromMap(raw);
        }
    }

    /** Exposed for tests: the exact map that would be written for {@code schema}. */
    public static Map<String, Object> toMap(Schema schema) {
        return schema.toMap();
    }
}
