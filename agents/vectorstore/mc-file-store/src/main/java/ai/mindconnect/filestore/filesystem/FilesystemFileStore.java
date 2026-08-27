package ai.mindconnect.filestore.filesystem;

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
import java.util.UUID;

/**
 * Filesystem backend: one directory per file id under the store root —
 * {@code <root>/<id>/meta.json} plus the content under its (sanitised)
 * original name. Ids are random and never derived from names, so uploads
 * cannot collide or traverse.
 */
public final class FilesystemFileStore implements FileStore {

    private static final Logger log = LoggerFactory.getLogger(FilesystemFileStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final Path root;

    public FilesystemFileStore(Path root) {
        this.root = root;
    }

    @Override
    public StoredFile save(String name, String contentType, InputStream content) throws IOException {
        String id = "file-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String safeName = Path.of(name == null || name.isBlank() ? "upload.bin" : name)
                .getFileName().toString().replaceAll("[^A-Za-z0-9._ -]", "_");
        Path dir = root.resolve(id);
        Files.createDirectories(dir);
        Path target = dir.resolve(safeName);
        long size = Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
        StoredFile file = new StoredFile(id, safeName, contentType, size, Instant.now());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("meta.json").toFile(), file);
        return file;
    }

    @Override
    public Optional<StoredFile> find(String id) {
        Path meta = root.resolve(sanitizeId(id)).resolve("meta.json");
        if (!Files.exists(meta)) {
            return Optional.empty();
        }
        try {
            return Optional.of(MAPPER.readValue(meta.toFile(), StoredFile.class));
        } catch (IOException e) {
            log.warn("Unreadable file metadata {}: {}", meta, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public InputStream content(String id) throws IOException {
        StoredFile file = find(id).orElseThrow(() ->
                new IOException("No stored file with id '" + id + "'"));
        return Files.newInputStream(root.resolve(sanitizeId(id)).resolve(file.name()));
    }

    @Override
    public List<StoredFile> list() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<StoredFile> files = new ArrayList<>();
        try (var dirs = Files.list(root)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                find(dir.getFileName().toString()).ifPresent(files::add);
            }
        } catch (IOException e) {
            log.warn("Could not list file store {}: {}", root, e.getMessage());
        }
        files.sort(Comparator.comparing(StoredFile::createdAt).reversed());
        return files;
    }

    @Override
    public void delete(String id) throws IOException {
        Path dir = root.resolve(sanitizeId(id));
        if (!Files.isDirectory(dir)) {
            return;
        }
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
