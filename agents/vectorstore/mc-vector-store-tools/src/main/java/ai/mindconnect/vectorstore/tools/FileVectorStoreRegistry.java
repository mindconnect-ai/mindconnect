package ai.mindconnect.vectorstore.tools;

import ai.mindconnect.filerepo.FileWrites;
import ai.mindconnect.filerepo.PathLocks;
import ai.mindconnect.vectorstore.embedding.EntityRef;
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
import java.util.Optional;

/**
 * File-persisted {@link VectorStoreRegistry} — one JSON file each under
 * {@code <root>/templates} and {@code <root>/instances}, following the same
 * conventions as the agent/workflow stores. Instances are registered on the
 * fly by the tools; templates are managed in the admin UI (or seeded).
 *
 * <p>A registry is built wherever vector stores are opened — by the app, by
 * each tool binding, by an upload — so several instances work on the same
 * files. Writes take the file's JVM-wide lock ({@link PathLocks}), which is
 * what lets {@link #registerInstance} decide and write as one step: two
 * uploads opening the same store register it once.
 */
public final class FileVectorStoreRegistry implements VectorStoreRegistry {

    private static final Logger log = LoggerFactory.getLogger(FileVectorStoreRegistry.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final com.fasterxml.jackson.core.type.TypeReference<List<EntityRef>> REFS =
            new com.fasterxml.jackson.core.type.TypeReference<>() {};

    private final Path templatesDir;
    private final Path instancesDir;
    private final Path membersDir;
    private final Path indexesDir;

    public FileVectorStoreRegistry(Path root) {
        this.templatesDir = root.resolve("templates");
        this.instancesDir = root.resolve("instances");
        this.membersDir = root.resolve("members");
        this.indexesDir = root.resolve("indexes");
    }

    // ── templates ──────────────────────────────────────────────────────────

    @Override
    public List<VectorStoreTemplate> templates() {
        return list(templatesDir, VectorStoreTemplate.class);
    }

    @Override
    public Optional<VectorStoreTemplate> template(String name) {
        return read(templatesDir, name, VectorStoreTemplate.class);
    }

    /**
     * Saves the template: the version it carries is checked against the stored one
     * and it is stored one higher, both under the file's lock ({@code null}: no check).
     *
     * @return the template as stored, with its new version
     * @throws ai.mindconnect.common.StaleVersionException when it was saved by someone else meanwhile
     */
    @Override
    public VectorStoreTemplate saveTemplate(VectorStoreTemplate template) {
        Path file = fileFor(templatesDir, template.name());
        return locked(file, () -> {
            Long stored = read(templatesDir, template.name(), VectorStoreTemplate.class)
                    .map(VectorStoreTemplate::version).orElse(null);
            VectorStoreTemplate saved = template.withVersion(ai.mindconnect.common.Versions.next(
                    stored, template.version(), "VectorStoreTemplate", template.name()));
            store(file, saved);
            return saved;
        });
    }

    @Override
    public void deleteTemplate(String name) {
        delete(templatesDir, name);
    }

    // ── instances ──────────────────────────────────────────────────────────

    @Override
    public List<VectorStoreInstance> instances() {
        return list(instancesDir, VectorStoreInstance.class);
    }

    @Override
    public Optional<VectorStoreInstance> instance(String name) {
        return read(instancesDir, name, VectorStoreInstance.class);
    }

    /**
     * Registers the instance if unknown; an existing record wins (settings own the
     * store). Looking and writing happen under the record's lock, so of two
     * concurrent registrations of one name exactly one is written and both callers
     * get it back.
     */
    @Override
    public VectorStoreInstance registerInstance(VectorStoreInstance candidate) {
        Path file = fileFor(instancesDir, candidate.name());
        return locked(file, () -> {
            Optional<VectorStoreInstance> existing = read(instancesDir, candidate.name(), VectorStoreInstance.class);
            if (existing.isPresent()) {
                return existing.get();
            }
            store(file, candidate);
            log.info("Registered vector store '{}' from template '{}' (scope {})",
                    candidate.name(), candidate.templateName(), candidate.scope());
            return candidate;
        });
    }

    /** Overwrites an instance record — instances may diverge from their template. */
    @Override
    public void saveInstance(VectorStoreInstance instance) {
        write(instancesDir, instance.name(), instance);
    }

    @Override
    public void deleteInstance(String name) {
        delete(instancesDir, name);
        delete(membersDir, name);
    }

    // ── indexes ────────────────────────────────────────────────────────────

    @Override
    public List<IndexDefinition> indexes() {
        return list(indexesDir, IndexDefinition.class);
    }

    @Override
    public Optional<IndexDefinition> index(String name) {
        return read(indexesDir, name, IndexDefinition.class);
    }

    @Override
    public void saveIndex(IndexDefinition index) {
        write(indexesDir, index.name(), index);
    }

    @Override
    public void deleteIndex(String name) {
        delete(indexesDir, name);
    }

    // ── members ────────────────────────────────────────────────────────────

    @Override
    public List<EntityRef> members(String store) {
        return readMembers(fileFor(membersDir, store));
    }

    @Override
    public void addMember(String store, EntityRef ref) {
        Path file = fileFor(membersDir, store);
        locked(file, () -> {
            List<EntityRef> members = new ArrayList<>(readMembers(file));
            if (!members.contains(ref)) {
                members.add(ref);
                store(file, members);
            }
            return null;
        });
    }

    @Override
    public void removeMember(String store, EntityRef ref) {
        Path file = fileFor(membersDir, store);
        locked(file, () -> {
            List<EntityRef> members = new ArrayList<>(readMembers(file));
            if (members.remove(ref)) {
                store(file, members);
            }
            return null;
        });
    }

    @Override
    public List<String> storesListing(EntityRef ref) {
        if (!Files.isDirectory(membersDir)) {
            return List.of();
        }
        List<String> stores = new ArrayList<>();
        try (var files = Files.list(membersDir)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                if (readMembers(file).contains(ref)) {
                    String name = file.getFileName().toString();
                    stores.add(name.substring(0, name.length() - ".json".length()));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return stores;
    }

    private static List<EntityRef> readMembers(Path file) {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            return MAPPER.readValue(file.toFile(), REFS);
        } catch (IOException e) {
            throw new UncheckedIOException("Unreadable member list " + file, e);
        }
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
        Path file = fileFor(dir, name);
        locked(file, () -> {
            store(file, value);
            return null;
        });
    }

    private void delete(Path dir, String name) {
        Path file = fileFor(dir, name);
        locked(file, () -> Files.deleteIfExists(file));
    }

    private static void store(Path file, Object value) throws IOException {
        FileWrites.write(file, out -> MAPPER.writerWithDefaultPrettyPrinter().writeValue(out, value));
    }

    private static <T> T locked(Path file, PathLocks.Action<T> action) {
        try {
            return PathLocks.withLock(file, PathLocks.DEFAULT_TIMEOUT, action);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + file, e);
        }
    }

    private static Path fileFor(Path dir, String name) {
        return dir.resolve(VectorStoreRegistry.key(name) + ".json");
    }
}
