package ai.mindconnect.workflow.execution;

import java.util.List;

/**
 * Minimal JSON serialization contract used by the engine at runtime
 * (e.g. parsing list variables, stringifying objects for script engines).
 *
 * The default implementation is deliberately absent from this module.
 * Bring your own — Jackson, Gson, JSON-B, etc. — via the serialization module.
 */
public interface JsonMapper {

    String stringify(Object object);

    <T> T parse(String json, Class<T> type);

    <T> List<T> parseList(String json, Class<T> itemType);
}
