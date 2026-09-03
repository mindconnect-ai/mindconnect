package ai.mindconnect.jdbc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * The document codec. Wraps an {@link ObjectMapper} so that repositories can
 * share the application's mapper — with its polymorphic type configuration —
 * without this library knowing anything about the types it stores.
 *
 * <p>{@link #defaults()} is what you get when there is no application mapper:
 * ISO dates, unknown properties ignored so a row written by a newer version
 * still reads on an older one.
 */
public final class Json {

    private final ObjectMapper mapper;

    public Json(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public static Json defaults() {
        return new Json(JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build());
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public String write(Object value) {
        if (value == null) return null;
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new JdbcException("Cannot render " + value.getClass().getSimpleName() + " as JSON: " + e.getMessage(), e);
        }
    }

    /** {@link #write} wrapped for binding: the value lands in a {@code jsonb} column. */
    public Jsonb jsonb(Object value) {
        return Jsonb.of(write(value));
    }

    public <T> T read(String json, Class<T> type) {
        if (json == null) return null;
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new JdbcException("Cannot read stored JSON as " + type.getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    public <T> T read(String json, TypeReference<T> type) {
        if (json == null) return null;
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new JdbcException("Cannot read stored JSON as " + type.getType() + ": " + e.getMessage(), e);
        }
    }
}
