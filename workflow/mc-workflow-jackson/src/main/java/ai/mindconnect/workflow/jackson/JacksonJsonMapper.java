package ai.mindconnect.workflow.jackson;

import ai.mindconnect.workflow.execution.JsonMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@link JsonMapper} implementation backed by Jackson's {@link ObjectMapper}.
 *
 * <p>Use {@link WorkflowObjectMapperFactory#create()} to obtain a correctly
 * configured mapper (with {@code StepData} polymorphism set up via mixins),
 * then pass it to this class.
 *
 * <p>Example:
 * <pre>{@code
 * ObjectMapper mapper = WorkflowObjectMapperFactory.create();
 * JacksonJsonMapper jsonMapper = new JacksonJsonMapper(mapper);
 * }</pre>
 */
@RequiredArgsConstructor
public class JacksonJsonMapper implements JsonMapper {

    private final ObjectMapper mapper;

    // -----------------------------------------------------------------------
    // JsonMapper contract
    // -----------------------------------------------------------------------

    @Override
    @SneakyThrows
    public String stringify(Object object) {
        return mapper.writeValueAsString(object);
    }

    @Override
    @SneakyThrows
    public <T> T parse(String json, Class<T> type) {
        return mapper.readValue(json, type);
    }

    @Override
    @SneakyThrows
    public <T> List<T> parseList(String json, Class<T> itemType) {
        return mapper.readValue(json,
                mapper.getTypeFactory().constructCollectionType(List.class, itemType));
    }

    // -----------------------------------------------------------------------
    // Extended convenience methods (Jackson-specific)
    // -----------------------------------------------------------------------

    @SneakyThrows
    public <T> T parse(InputStream is, Class<T> type) {
        return mapper.readValue(is, type);
    }

    @SneakyThrows
    public <T> List<T> parseList(InputStream is, Class<T> itemType) {
        return mapper.readValue(is,
                mapper.getTypeFactory().constructCollectionType(List.class, itemType));
    }

    @SneakyThrows
    public <T> T readFromPath(Path path, Class<T> type) {
        return mapper.readValue(path.toFile(), type);
    }

    @SneakyThrows
    public String writeToPath(Object object, Path path) {
        String json = mapper.writeValueAsString(object);
        Files.writeString(path, json);
        return json;
    }

    public ObjectMapper getMapper() {
        return mapper;
    }
}
