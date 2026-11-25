package ai.mindconnect.json;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Where every {@link ObjectMapper} of an installation comes from, so that every
 * one knows the modules the installation brings: each that announces itself in
 * {@code META-INF/services/com.fasterxml.jackson.databind.Module} — the Java
 * time types, and the ids of the agents. A mapper built by hand misses a module
 * without a word: an id is then written as an object, and nothing fails.
 *
 * <p>Nothing else is set: each caller keeps the features and modules it chose,
 * because mappers that differ today write different JSON, and that JSON is
 * stored.
 *
 * <p>Modules are looked up through this class's loader and the thread's context
 * loader: in a Spring Boot jar the context loader of a pool thread does not see
 * the application's libraries, and under a restart loader this class may not
 * see the application's own modules.
 */
public final class JsonMapperFactory {

    private JsonMapperFactory() {
    }

    /** A mapper with Jackson's defaults and the modules found. */
    public static ObjectMapper create() {
        return builder().build();
    }

    /** A mapper over another format, such as YAML, with the modules found. */
    public static ObjectMapper create(JsonFactory format) {
        return new ObjectMapper(format).registerModules(modules());
    }

    /** For a caller that sets more: Jackson's defaults and the modules found, the rest is the caller's. */
    public static JsonMapper.Builder builder() {
        return JsonMapper.builder().addModules(modules());
    }

    /** Duplicates are fine: a mapper registers a module of a type once. */
    private static List<Module> modules() {
        ClassLoader own = JsonMapperFactory.class.getClassLoader();
        List<Module> found = new ArrayList<>(ObjectMapper.findModules(own));
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        if (context != null && context != own) {
            found.addAll(ObjectMapper.findModules(context));
        }
        return found;
    }
}
