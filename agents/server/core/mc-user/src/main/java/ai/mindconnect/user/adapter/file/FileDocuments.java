package ai.mindconnect.user.adapter.file;

import ai.mindconnect.common.util.AtomicFiles;
import com.fasterxml.jackson.databind.DeserializationFeature;
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
 * One directory of JSON documents of one type: written atomically, read
 * leniently (unknown fields are ignored, so a document from another version
 * still loads), and an unreadable file is skipped with a warning instead of
 * taking every other document down with it.
 */
class FileDocuments<T> {

    private static final Logger log = LoggerFactory.getLogger(FileDocuments.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path dir;
    private final Class<T> type;

    FileDocuments(Path dir, Class<T> type) {
        this.dir = dir;
        this.type = type;
    }

    Optional<T> read(String key) {
        Path file = fileFor(key);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return readFile(file);
    }

    List<T> readAll() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<T> out = new ArrayList<>();
        try (var files = Files.list(dir)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".json")).toList()) {
                readFile(file).ifPresent(out::add);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list " + dir, e);
        }
        return out;
    }

    void write(String key, T document) {
        try {
            AtomicFiles.write(fileFor(key), out -> MAPPER.writeValue(out, document));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + fileFor(key), e);
        }
    }

    void delete(String key) {
        try {
            Files.deleteIfExists(fileFor(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete " + fileFor(key), e);
        }
    }

    private Optional<T> readFile(Path file) {
        try {
            return Optional.of(MAPPER.readValue(file.toFile(), type));
        } catch (IOException e) {
            log.warn("Skipping unreadable {}: {}", file, e.getMessage());
            return Optional.empty();
        }
    }

    private Path fileFor(String key) {
        return dir.resolve(key + ".json");
    }
}
