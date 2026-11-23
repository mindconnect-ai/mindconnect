package ai.mindconnect.agent.cli.local.file;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import ai.mindconnect.agent.runtime.api.domain.ModelSettings;

/**
 * One {@code .mc/settings.json}: the model settings and the tool permissions
 * of one place — a project or a home.
 *
 * <pre>
 * {
 *   "model": "research-model",
 *   "temperature": 0.2,
 *   "permissions": {
 *     "allow": ["read_file", "search_*"],
 *     "ask":   ["write_file"],
 *     "deny":  ["delete_*"]
 *   }
 * }
 * </pre>
 *
 * <p>A file that is not there is a file that says nothing.
 */
final class SettingsFile {

    private static final String FILE_NAME = "settings.json";
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() { };

    private final Path file;
    private final ObjectMapper json;
    private final Map<String, Object> content;

    private SettingsFile(Path file, ObjectMapper json, Map<String, Object> content) {
        this.file = file;
        this.json = json;
        this.content = content;
    }

    static SettingsFile in(Path configDirectory, ObjectMapper json) {
        Path file = configDirectory.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return new SettingsFile(file, json, new LinkedHashMap<>());
        }
        try {
            return new SettingsFile(file, json, json.readValue(file.toFile(), JSON_OBJECT));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    ModelSettings modelSettings() {
        return new ModelSettings(
                (String) content.get("model"),
                content.get("temperature") instanceof Number temperature ? temperature.doubleValue() : null,
                content.get("maxOutputTokens") instanceof Number limit ? limit.intValue() : null,
                (String) content.get("reasoningEffort"),
                Map.of());
    }

    /** The tool patterns of one permission list: {@code allow}, {@code ask} or {@code deny}. */
    @SuppressWarnings("unchecked")
    List<String> permissions(String list) {
        Object permissions = content.get("permissions");
        return permissions instanceof Map<?, ?> lists && lists.get(list) instanceof List<?> patterns
                ? (List<String>) patterns
                : List.of();
    }

    /** Adds the tool to the allow list and writes the file, creating it and its folder when needed. */
    @SuppressWarnings("unchecked")
    void allow(String toolName) {
        Map<String, Object> permissions = content.get("permissions") instanceof Map<?, ?> existing
                ? new LinkedHashMap<>((Map<String, Object>) existing)
                : new LinkedHashMap<>();
        List<String> allowed = new ArrayList<>(permissions("allow"));
        if (!allowed.contains(toolName)) {
            allowed.add(toolName);
        }
        permissions.put("allow", allowed);
        content.put("permissions", permissions);
        try {
            Files.createDirectories(file.getParent());
            json.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), content);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file, e);
        }
    }
}
