package ai.mindconnect.jackson;

import java.util.List;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Where every {@link ObjectMapper} of an installation comes from, so that every
 * one knows the Jackson modules the installation brings — the ids of the agents
 * among them. A mapper built by hand misses a module without a word: an id is
 * then written as an object, and nothing fails.
 *
 * <p>The modules are given, not looked up: inside a runtime they are what its
 * plugins contribute ({@link JacksonPlugin}). Nothing else is set: each caller
 * keeps the features it chose, because mappers that differ today write
 * different JSON, and that JSON is stored.
 */
public final class JsonMapperFactory {

    private final List<Module> modules;

    public JsonMapperFactory(List<? extends Module> modules) {
        this.modules = List.copyOf(modules);
    }

    /** A mapper with Jackson's defaults and the modules. */
    public ObjectMapper create() {
        return builder().build();
    }

    /** A mapper over another format, such as YAML, with the modules. */
    public ObjectMapper create(JsonFactory format) {
        return new ObjectMapper(format).registerModules(modules);
    }

    /** For a caller that sets more: Jackson's defaults and the modules, the rest is the caller's. */
    public JsonMapper.Builder builder() {
        return JsonMapper.builder().addModules(modules);
    }
}
