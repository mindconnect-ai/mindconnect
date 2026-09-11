package ai.mindconnect.agent.runtime.adapter.file;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.tool.ToolRepository;
import ai.mindconnect.agent.tool.ToolSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Tool settings as one JSON document:
 * {@code <storage>/<namespace>/system/tool-settings.json}.
 *
 * <p>Bound to one namespace at construction, like every other store: one
 * process serves one namespace, so nothing above this class names it.
 *
 * <p>One file rather than one per tool, because these are small and are read
 * together: every lookup wants the whole overlay, never a single entry. An
 * operator can read the file and see at a glance what deviates from the
 * shipped state — which is usually a handful of lines, and on a fresh
 * installation no file at all.
 */
public final class FileToolRepository implements ToolRepository {

    private static final Logger log = Logger.getLogger(FileToolRepository.class.getName());

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path file;

    public FileToolRepository(Path storageDir, Namespace namespace) {
        this.file = storageDir.resolve(namespace.value()).resolve("system")
                .resolve("tool-settings.json").toAbsolutePath();
    }

    @Override
    public ToolSettings settings(String toolName) {
        return all().getOrDefault(toolName, ToolSettings.none());
    }

    @Override
    public Map<String, ToolSettings> all() {
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        try {
            JsonNode root = MAPPER.readTree(file.toFile());
            Map<String, ToolSettings> settings = new LinkedHashMap<>();
            root.fields().forEachRemaining(entry ->
                    settings.put(entry.getKey(), read(entry.getValue())));
            // Not Map.copyOf: that returns an unordered map, and save() writes
            // this map straight back out. The document would reshuffle itself
            // on every save — against the point of a file an operator reads.
            return Collections.unmodifiableMap(settings);
        } catch (IOException | RuntimeException e) {
            // Unreadable settings must not take the tools with them: the
            // shipped state is a usable fallback, an empty catalog is not.
            log.warning("tool settings " + file + " unusable, falling back to defaults: " + e);
            return Map.of();
        }
    }

    @Override
    public void save(String toolName, ToolSettings settings) {
        Map<String, ToolSettings> all = new LinkedHashMap<>(all());
        if (settings == null || settings.isEmpty()) {
            all.remove(toolName);            // nothing to say is nothing to store
        } else {
            all.put(toolName, settings);
        }
        write(all);
    }

    @Override
    public void delete(String toolName) {
        save(toolName, null);
    }

    /**
     * The file's modification time. Two saves within the same millisecond
     * report the same version — see the TODO on store versions; acceptable
     * while nothing saves in bulk.
     */
    @Override
    public long version() {
        try {
            return Files.isRegularFile(file) ? Files.getLastModifiedTime(file).toMillis() : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }

    private void write(Map<String, ToolSettings> all) {
        try {
            if (all.isEmpty()) {
                Files.deleteIfExists(file);   // back to the shipped state
                return;
            }
            Files.createDirectories(file.getParent());
            ObjectNode root = MAPPER.createObjectNode();
            all.forEach((name, settings) -> root.set(name, write(settings)));
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            MAPPER.writeValue(tmp.toFile(), root);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write tool settings " + file, e);
        }
    }

    private static ObjectNode write(ToolSettings settings) {
        ObjectNode node = MAPPER.createObjectNode();
        if (settings.enabled() != null) {
            node.put("enabled", settings.enabled());
        }
        if (settings.description() != null && !settings.description().isBlank()) {
            node.put("description", settings.description());
        }
        if (!settings.parameterDescriptions().isEmpty()) {
            ObjectNode parameters = node.putObject("parameterDescriptions");
            settings.parameterDescriptions().forEach(parameters::put);
        }
        return node;
    }

    private static ToolSettings read(JsonNode node) {
        Map<String, String> parameters = new LinkedHashMap<>();
        node.path("parameterDescriptions").fields().forEachRemaining(entry ->
                parameters.put(entry.getKey(), entry.getValue().asText()));
        return new ToolSettings(
                node.has("enabled") ? node.get("enabled").asBoolean() : null,
                node.path("description").asText(null),
                parameters);
    }
}
