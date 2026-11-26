package ai.mindconnect.agent.jackson;

import java.io.IOException;
import java.util.Map;
import java.util.function.Function;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.scope.Namespace;
import ai.mindconnect.agent.tool.AgentToolId;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer;
import com.fasterxml.jackson.databind.deser.std.StringDeserializer;
import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

/**
 * The ids of the agents in JSON: each is its plain value — as a value, a field,
 * a list entry and a map key — because files, database columns and REST
 * clients hold them so. The records know nothing of Jackson; without this
 * module a mapper writes a one-field record as {@code {"value": "…"}}.
 *
 * <p>Read back from a string, as Jackson reads a {@code String}. An
 * {@link AgentId} is also read from {@code {"value": "…"}}, which the stores of
 * the first builds on the new runtime wrote for it; the other ids never were,
 * and stay refused in that form. A value an id refuses fails as Jackson fails a
 * creator that throws.
 */
public class IdJsonModule extends SimpleModule {

    public IdJsonModule() {
        super("mindconnect-ids");
        id(SessionId.class, SessionId::of, SessionId::value, false);
        id(UserId.class, UserId::of, UserId::value, false);
        id(AgentId.class, AgentId::of, AgentId::value, true);
        id(AgentToolId.class, AgentToolId::of, AgentToolId::value, false);
        id(Namespace.class, Namespace::new, Namespace::value, false);
    }

    private <T> void id(Class<T> type, Function<String, T> of, Function<T, String> value, boolean wrappedToo) {
        addSerializer(type, new StdSerializer<>(type) {
            @Override
            public void serialize(T id, JsonGenerator out, SerializerProvider provider) throws IOException {
                out.writeString(value.apply(id));
            }
        });
        addKeySerializer(type, new StdSerializer<>(type) {
            @Override
            public void serialize(T id, JsonGenerator out, SerializerProvider provider) throws IOException {
                out.writeFieldName(value.apply(id));
            }
        });
        addDeserializer(type, new StdScalarDeserializer<>(type) {
            @Override
            public T deserialize(JsonParser in, DeserializationContext context) throws IOException {
                String text = wrappedToo && in.currentToken() == JsonToken.START_OBJECT
                        ? String.valueOf(context.readValue(in, Map.class).get("value"))
                        : StringDeserializer.instance.deserialize(in, context);
                try {
                    return of.apply(text);
                } catch (IllegalArgumentException refused) {
                    throw ValueInstantiationException.from(in, "Cannot construct instance of `" + type.getName()
                            + "`, problem: " + refused.getMessage(), context.constructType(type), refused);
                }
            }
        });
        addKeyDeserializer(type, new KeyDeserializer() {
            @Override
            public Object deserializeKey(String key, DeserializationContext context) throws IOException {
                try {
                    return of.apply(key);
                } catch (IllegalArgumentException refused) {
                    return context.handleWeirdKey(type, key, refused.getMessage());
                }
            }
        });
    }
}
