package ai.mindconnect.filerepo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * One JSON document per key, one file per document — the file counterpart of
 * {@code DocumentTable} in {@code mc-jdbc}.
 *
 * <pre>{@code
 * Documents<SessionId, AgentSession> sessions = Documents.of(AgentSession.class)
 *         .path((SessionId id) -> "sessions/" + id.value() + "/session.json")
 *         .build(FileRepo.open(dataDir, namespace), objectMapper);
 *
 * sessions.create(id, session);                              // refuses an existing one
 * sessions.find(id);                                         // Optional, no lock
 * sessions.update(id, s -> s.withTitle("Weekly report"));    // read-change-write, one writer at a time
 * sessions.delete(id);
 * }</pre>
 *
 * <p><b>Reading</b> takes no lock and never sees a half-written file. A missing
 * document is {@code Optional.empty()}; an unreadable one throws — it never
 * passes for "no document", which is how a store once rewrote a list it had
 * failed to read as empty.
 *
 * <p><b>Writing</b> holds the file's {@link PathLocks write lock} for the length
 * of one small write, so writers of the same document take turns and writers
 * of different documents do not wait for each other. {@link #update} reads,
 * changes and writes under that one lock: two concurrent updates both land,
 * neither overwrites the other.
 *
 * <p>The functions handed to {@link #update} and {@link #createIfAbsent} run
 * under the lock. Keep them short and free of side effects: build the new
 * value, nothing else — no model call, no tool, no network. Writing another
 * document from inside one throws {@link NestedWriteException}.
 */
public class Documents<K, V> {

    private final FileRepo repo;
    private final Function<K, String> path;
    private final ObjectReader reader;
    private final ObjectWriter writer;
    private final Duration lockTimeout;

    private Documents(Builder<K, V> b, FileRepo repo, ObjectMapper mapper) {
        this.repo = repo;
        this.path = b.path;
        this.reader = mapper.readerFor(b.type);
        ObjectWriter w = mapper.writerFor(b.type);
        this.writer = b.prettyPrint ? w.withDefaultPrettyPrinter() : w;
        this.lockTimeout = b.lockTimeout;
    }

    public static <V> Builder<Object, V> of(Class<V> type) {
        return new Builder<>(type, null, PathLocks.DEFAULT_TIMEOUT, false);
    }

    /** The file of the document under {@code key}. */
    public Path pathOf(K key) {
        return repo.resolve(path.apply(key));
    }

    // ── reading ─────────────────────────────────────────────────────────────

    public Optional<V> find(K key) {
        return read(pathOf(key));
    }

    public boolean exists(K key) {
        return Files.exists(pathOf(key));
    }

    /**
     * Every document stored as a {@code *.json} file directly in {@code dir}, by
     * file name — for layouts like {@code agents/<id>.json}. Temporary files and
     * documents deleted while listing are skipped.
     */
    public List<V> findAll(String dir) {
        List<V> found = new ArrayList<>();
        for (Path file : list(repo.resolve(dir))) {
            String name = file.getFileName().toString();
            if (name.endsWith(".json") && !name.startsWith(".") && Files.isRegularFile(file)) {
                read(file).ifPresent(found::add);
            }
        }
        return found;
    }

    /**
     * Every document stored as {@code fileName} in a subdirectory of {@code dir},
     * by directory name — for layouts like {@code sessions/<id>/session.json}.
     */
    public List<V> findAll(String dir, String fileName) {
        List<V> found = new ArrayList<>();
        for (Path sub : list(repo.resolve(dir))) {
            if (Files.isDirectory(sub)) {
                read(sub.resolve(fileName)).ifPresent(found::add);
            }
        }
        return found;
    }

    // ── writing ─────────────────────────────────────────────────────────────

    /**
     * Stores a new document.
     *
     * @throws DocumentExistsException when there is one under {@code key} already
     */
    public V create(K key, V value) {
        Objects.requireNonNull(value, "value");
        Path file = pathOf(key);
        return locked(file, () -> {
            if (Files.exists(file)) {
                throw new DocumentExistsException(file);
            }
            store(file, value);
            return value;
        });
    }

    /**
     * The document under {@code key}; if there is none, the one {@code value}
     * builds, stored. Deciding and storing happen under one lock, so two callers
     * racing for the same key end up with the same document.
     */
    public V createIfAbsent(K key, Supplier<V> value) {
        Path file = pathOf(key);
        return locked(file, () -> {
            Optional<V> existing = read(file);
            if (existing.isPresent()) {
                return existing.get();
            }
            V created = Objects.requireNonNull(value.get(), "createIfAbsent: the supplier returned null");
            store(file, created);
            return created;
        });
    }

    /**
     * Reads the document, applies {@code change} and writes the result, all under
     * the document's write lock — concurrent updates of one document take turns
     * and each sees the result of the one before.
     *
     * <p>Returning the very instance it was given means "no change": nothing is
     * written.
     *
     * @return the changed document, or empty when there is no document under {@code key}
     */
    public Optional<V> update(K key, UnaryOperator<V> change) {
        Path file = pathOf(key);
        return locked(file, () -> {
            Optional<V> current = read(file);
            if (current.isEmpty()) {
                return Optional.<V>empty();
            }
            V changed = Objects.requireNonNull(change.apply(current.get()), "update: the change returned null");
            if (changed != current.get()) {
                store(file, changed);
            }
            return Optional.of(changed);
        });
    }

    /**
     * Reads the document — empty when there is none — lets {@code change} decide
     * what to store, and writes that, all under the document's write lock. For a
     * save that depends on what is stored, whether or not anything is: a version
     * check, say. Returning the very instance that was read writes nothing.
     *
     * @return what is stored afterwards
     */
    public V compute(K key, Function<Optional<V>, V> change) {
        Path file = pathOf(key);
        return locked(file, () -> {
            Optional<V> current = read(file);
            V next = Objects.requireNonNull(change.apply(current), "compute: the change returned null");
            if (current.isEmpty() || next != current.get()) {
                store(file, next);
            }
            return next;
        });
    }

    /**
     * Stores {@code value} under {@code key}, replacing whatever is there. For
     * documents that are rebuilt whole rather than changed — a snapshot, a list
     * a tool replaces. For anything else, {@link #update} does not lose a
     * concurrent change and this does.
     */
    public V put(K key, V value) {
        Objects.requireNonNull(value, "value");
        Path file = pathOf(key);
        return locked(file, () -> {
            store(file, value);
            return value;
        });
    }

    /** Deletes the document's file; its directory stays. Returns whether there was one. */
    public boolean delete(K key) {
        Path file = pathOf(key);
        return locked(file, () -> Files.deleteIfExists(file));
    }

    // ── files ───────────────────────────────────────────────────────────────

    private Optional<V> read(Path file) {
        V value;
        try (InputStream in = Files.newInputStream(file)) {
            value = reader.readValue(in);
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new FileRepoException("Cannot read document " + file, e);
        }
        if (value == null) {
            throw new FileRepoException("Document " + file + " contains null");
        }
        return Optional.of(value);
    }

    private void store(Path file, V value) throws IOException {
        FileWrites.write(file, out -> writer.writeValue(out, value));
    }

    private <T> T locked(Path file, PathLocks.Action<T> action) {
        try {
            return PathLocks.withLock(file, lockTimeout, action);
        } catch (IOException e) {
            throw new FileRepoException("Cannot write document " + file, e);
        }
    }

    private static List<Path> list(Path dir) {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.sorted().toList();
        } catch (NoSuchFileException e) {
            return List.of();
        } catch (IOException e) {
            throw new FileRepoException("Cannot list " + dir, e);
        }
    }

    // ── builder ─────────────────────────────────────────────────────────────

    public static class Builder<K, V> {

        private final Class<V> type;
        private final Function<K, String> path;
        private final Duration lockTimeout;
        private final boolean prettyPrint;

        private Builder(Class<V> type, Function<K, String> path, Duration lockTimeout, boolean prettyPrint) {
            this.type = type;
            this.path = path;
            this.lockTimeout = lockTimeout;
            this.prettyPrint = prettyPrint;
        }

        /**
         * Where the document of a key lives, relative to the data directory. It must
         * follow from the key alone — that is what lets a write lock the right file
         * without looking anything up. Give the lambda parameter its type:
         * {@code .path((SessionId id) -> "sessions/" + id.value() + "/session.json")}.
         */
        public <K2> Builder<K2, V> path(Function<K2, String> path) {
            return new Builder<>(type, Objects.requireNonNull(path, "path"), lockTimeout, prettyPrint);
        }

        /** How long a write waits for the document's lock; {@link PathLocks#DEFAULT_TIMEOUT} by default. */
        public Builder<K, V> lockTimeout(Duration lockTimeout) {
            return new Builder<>(type, path, Objects.requireNonNull(lockTimeout, "lockTimeout"), prettyPrint);
        }

        /** Indented JSON, for documents people read by hand. */
        public Builder<K, V> prettyPrint() {
            return new Builder<>(type, path, lockTimeout, true);
        }

        public Documents<K, V> build(FileRepo repo, ObjectMapper mapper) {
            if (path == null) throw new IllegalStateException("path() is required");
            return new Documents<>(this, Objects.requireNonNull(repo, "repo"), Objects.requireNonNull(mapper, "mapper"));
        }
    }
}
