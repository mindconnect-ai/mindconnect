package ai.mindconnect.vectorstore.embedding;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * An {@link EntryStore} in a directory — the embedding index of the file
 * persistence, one directory per namespace. Each entity and model is one JSON
 * file, named by a hash of the two (ids may hold any character) and spread
 * over 256 subdirectories; the file holds the ref itself. Writes go to a
 * temporary file that is then moved into place, so a crash leaves the old
 * entry or the new one.
 *
 * <pre>
 * &lt;dir&gt;/fields.json
 * &lt;dir&gt;/entries/3f/3f9a…c2.json
 * </pre>
 */
public final class FileEntryStore implements EntryStore {

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final java.util.Map<Path, MemoryEmbeddingIndex> INDEXES = new java.util.concurrent.ConcurrentHashMap<>();

    private final Path dir;

    public FileEntryStore(Path dir) {
        this.dir = dir;
    }

    /**
     * The index kept in {@code dir} — one per directory and JVM, read from the
     * directory on first use. A runtime builds its vector stores more than once
     * (the upload path, each tool binding); a heap copy of its own for each
     * would not see what the others write.
     */
    public static MemoryEmbeddingIndex index(Path dir) {
        return INDEXES.computeIfAbsent(dir.toAbsolutePath().normalize(),
                d -> new MemoryEmbeddingIndex(new FileEntryStore(d)));
    }

    /** Forgets the heap copy of {@code dir} — its namespace was deleted, or a test starts afresh. */
    public static void forget(Path dir) {
        INDEXES.remove(dir.toAbsolutePath().normalize());
    }

    @Override
    public List<Entry> entries() {
        Path entries = dir.resolve("entries");
        if (!Files.isDirectory(entries)) {
            return List.of();
        }
        List<Entry> all = new ArrayList<>();
        try (Stream<Path> files = Files.walk(entries, 2)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                all.add(JSON.readValue(file.toFile(), Entry.class));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the embedding index in " + dir, e);
        }
        return all;
    }

    @Override
    public void write(Entry entry) {
        try {
            writeAtomically(file(entry.ref(), entry.embeddingModel()), JSON.writeValueAsBytes(entry));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + entry.ref() + " to the embedding index in " + dir, e);
        }
    }

    @Override
    public void remove(EntityRef ref, String embeddingModel) {
        try {
            Files.deleteIfExists(file(ref, embeddingModel));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot remove " + ref + " from the embedding index in " + dir, e);
        }
    }

    @Override
    public List<MetadataField> fields() {
        Path file = dir.resolve("fields.json");
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            return JSON.readValue(file.toFile(), new TypeReference<List<MetadataField>>() {});
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    @Override
    public void writeFields(List<MetadataField> fields) {
        try {
            writeAtomically(dir.resolve("fields.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(fields));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write the declared fields to " + dir, e);
        }
    }

    private Path file(EntityRef ref, String embeddingModel) {
        String hash = sha256(ref.type() + "\u001f" + ref.source() + "\u001f" + ref.container() + "\u001f"
                + ref.id() + "\u001f" + embeddingModel);
        return dir.resolve("entries").resolve(hash.substring(0, 2)).resolve(hash + ".json");
    }

    private static void writeAtomically(Path target, byte[] content) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.write(temp, content);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
