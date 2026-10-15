package ai.mindconnect.filestore.filesystem;

import ai.mindconnect.common.util.AtomicFiles;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.EntityId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.filestore.FileId;
import ai.mindconnect.filestore.FileStore;
import ai.mindconnect.filestore.StoredFile;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Filesystem backend: one directory per file id under the namespace's
 * {@code files} directory — {@code <baseDir>/<namespace>/files/<id>/meta.json}
 * plus the content under its (sanitised)
 * original name. Ids are random and never derived from names, so uploads
 * cannot collide or traverse.
 */
public final class FilesystemFileStore implements FileStore {

    private static final Logger log = LoggerFactory.getLogger(FilesystemFileStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final Path root;

    /** @param baseDir the data directory; the files live in {@code <baseDir>/<namespace>/files} */
    public FilesystemFileStore(Path baseDir, Namespace namespace) {
        this.root = baseDir.resolve(namespace.value()).resolve("files");
    }

    /** The creator lands in {@code meta.json}; metadata written before creators existed loads without one. */
    @Override
    public StoredFile save(String name, String contentType, InputStream content, UserId creator) throws IOException {
        FileId id = FileId.of("file-" + EntityId.randomValue().replace("-", "").substring(0, 20));
        String safeName = Path.of(name == null || name.isBlank() ? "upload.bin" : name)
                .getFileName().toString().replaceAll("[^A-Za-z0-9._ -]", "_");
        Path dir = root.resolve(id.value());
        Files.createDirectories(dir);
        Path target = dir.resolve(safeName);
        long size = Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
        StoredFile file = new StoredFile(id, safeName, contentType, size, Instant.now(), creator);
        // The metadata is what makes the upload exist for readers; it lands whole or not at all.
        AtomicFiles.write(dir.resolve("meta.json"), out -> MAPPER.writerWithDefaultPrettyPrinter().writeValue(out, file));
        return file;
    }

    @Override
    public Optional<StoredFile> find(FileId id) {
        return read(id.value()).filter(f -> f.id().equals(id));
    }

    /** The metadata under {@code dir}. Metadata is read, never written back. */
    private Optional<StoredFile> read(String dir) {
        Path meta = root.resolve(sanitizeId(dir)).resolve("meta.json");
        if (!Files.exists(meta)) {
            return Optional.empty();
        }
        try {
            return Optional.of(MAPPER.readValue(meta.toFile(), StoredFile.class));
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Unreadable file metadata {}: {}", meta, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public InputStream content(FileId id) throws IOException {
        StoredFile file = find(id).orElseThrow(() ->
                new IOException("No stored file with id '" + id + "'"));
        return Files.newInputStream(root.resolve(sanitizeId(id.value())).resolve(file.name()));
    }

    @Override
    public List<StoredFile> list() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<StoredFile> files = new ArrayList<>();
        try (var dirs = Files.list(root)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                read(dir.getFileName().toString())
                        .ifPresent(files::add);
            }
        } catch (IOException e) {
            log.warn("Could not list file store {}: {}", root, e.getMessage());
        }
        files.sort(Comparator.comparing(StoredFile::createdAt).reversed());
        return files;
    }

    @Override
    public void delete(FileId id) throws IOException {
        if (find(id).isEmpty()) {
            return;
        }
        Path dir = root.resolve(sanitizeId(id.value()));
        try (var entries = Files.list(dir)) {
            for (Path entry : entries.toList()) {
                Files.deleteIfExists(entry);
            }
        }
        Files.deleteIfExists(dir);
    }

    /** Ids are our own format; anything else must not escape the root. */
    private static String sanitizeId(String id) {
        return id.replaceAll("[^A-Za-z0-9-]", "");
    }
}
