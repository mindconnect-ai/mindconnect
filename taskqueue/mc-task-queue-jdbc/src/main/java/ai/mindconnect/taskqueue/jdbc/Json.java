package ai.mindconnect.taskqueue.jdbc;

import ai.mindconnect.taskqueue.TaskFailure;
import ai.mindconnect.taskqueue.TaskNotification;
import ai.mindconnect.taskqueue.TaskQueueException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The jsonb columns, read and written. Small on purpose — the only reason a
 * store needs JSON at all is that payload, state and mailbox are documents,
 * and giving each of them its own table would buy nothing but joins.
 *
 * <p>Unknown properties are ignored, so a record that grew a field in a newer
 * version can still be read by an older node during a rolling deploy.
 */
final class Json {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };
    private static final TypeReference<List<TaskNotification>> NOTIFICATIONS = new TypeReference<>() { };

    private Json() {
    }

    static String write(Object value) {
        if (value == null) return null;
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            // A payload that will not serialize is the "data only" rule being
            // broken; say so here rather than three hours later on another node.
            throw new TaskQueueException("Cannot store as JSON: " + e.getMessage());
        }
    }

    static Map<String, Object> readMap(String json) {
        return json == null ? Map.of() : read(json, MAP);
    }

    static Set<String> readStringSet(String json) {
        return json == null ? Set.of() : new LinkedHashSet<>(read(json, STRINGS));
    }

    static List<TaskNotification> readNotifications(String json) {
        return json == null ? List.of() : read(json, NOTIFICATIONS);
    }

    static TaskFailure readFailure(String json) {
        if (json == null) return null;
        try {
            return MAPPER.readValue(json, TaskFailure.class);
        } catch (Exception e) {
            throw new TaskQueueException("Cannot read stored JSON: " + e.getMessage());
        }
    }

    /** A stored JSONB value back as the plain Java value it came from. */
    static Object readValue(String json) {
        try {
            return MAPPER.readValue(json, Object.class);
        } catch (Exception e) {
            throw new TaskQueueException("Cannot read stored JSON: " + e.getMessage());
        }
    }

    private static <T> T read(String json, TypeReference<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new TaskQueueException("Cannot read stored JSON: " + e.getMessage());
        }
    }
}
