package ai.mindconnect.vectorstore.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * File-persisted registry of {@link VectorStoreTemplate}s and
 * {@link VectorStoreInstance}s — one JSON file each under
 * {@code <root>/templates} and {@code <root>/instances}, following the same
 * conventions as the agent/workflow stores. Instances are registered on the
 * fly by the tools; templates are managed in the admin UI (or seeded).
 */
public final class FileVectorStoreRegistry {

    private static final Logger log = LoggerFactory.getLogger(FileVectorStoreRegistry.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final Path templatesDir;
    private final Path instancesDir;

    public FileVectorStoreRegistry(Path root) {
        this.templatesDir = root.resolve("templates");
        this.instancesDir = root.resolve("instances");
    }

    // ── templates ──────────────────────────────────────────────────────────

    public List<VectorStoreTemplate> templates() {
        return list(templatesDir, VectorStoreTemplate.class);
    }

    public Optional<VectorStoreTemplate> template(String name) {
        return read(templatesDir, name, VectorStoreTemplate.class);
    }

    public void saveTemplate(VectorStoreTemplate template) {
        write(templatesDir, template.name(), template);
    }

    public void deleteTemplate(String name) {
        delete(templatesDir, name);
    }

    // ── instances ──────────────────────────────────────────────────────────

    public List<VectorStoreInstance> instances() {
        return list(instancesDir, VectorStoreInstance.class);
    }

    public Optional<VectorStoreInstance> instance(String name) {
        return read(instancesDir, name, VectorStoreInstance.class);
    }

    /** Registers the instance if unknown; an existing record wins (settings own the store). */
    public VectorStoreInstance registerInstance(VectorStoreInstance candidate) {
        return instance(candidate.name()).orElseGet(() -> {
            write(instancesDir, candidate.name(), candidate);
            log.info("Registered vector store '{}' from template '{}' (scope {})",
                    candidate.name(), candidate.templateName(), candidate.scope());
            return candidate;
        });
    }

    /** Overwrites an instance record — instances may diverge from their template. */
    public void saveInstance(VectorStoreInstance instance) {
        write(instancesDir, instance.name(), instance);
    }

    /** Instances of one scope (e.g. all SESSION stores of a session id). */
    public List<VectorStoreInstance> instances(VectorStoreInstance.Scope scope, String scopeRef) {
        return instances().stream()
                .filter(i -> i.scope() == scope
                        && (scopeRef == null || scopeRef.equals(i.scopeRef())))
                .toList();
    }

    public void deleteInstance(String name) {
        delete(instancesDir, name);
    }

    // ── file plumbing ──────────────────────────────────────────────────────

    private <T> List<T> list(Path dir, Class<T> type) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<T> out = new ArrayList<>();
        try (var files = Files.list(dir)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                try {
                    out.add(MAPPER.readValue(file.toFile(), type));
                } catch (IOException e) {
                    log.warn("Skipping unreadable {}: {}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    private <T> Optional<T> read(Path dir, String name, Class<T> type) {
        Path file = fileFor(dir, name);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(MAPPER.readValue(file.toFile(), type));
        } catch (IOException e) {
            log.warn("Unreadable {}: {}", file, e.getMessage());
            return Optional.empty();
        }
    }

    private void write(Path dir, String name, Object value) {
        try {
            Files.createDirectories(dir);
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(fileFor(dir, name).toFile(), value);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + fileFor(dir, name), e);
        }
    }

    private void delete(Path dir, String name) {
        try {
            Files.deleteIfExists(fileFor(dir, name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path fileFor(Path dir, String name) {
        return dir.resolve(name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-") + ".json");
    }
}
