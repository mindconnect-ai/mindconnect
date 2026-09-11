package ai.mindconnect.filerepo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.ToLongFunction;
import java.util.function.UnaryOperator;

/**
 * An ordered log of records under a parent key — the messages of a
 * conversation, its summaries — one JSON file per record, named by the record's
 * key and id: {@code 0000000012_<id>.json}.
 *
 * <pre>{@code
 * RecordLog<ConversationId, Message> messages = RecordLog.of(Message.class)
 *         .dir((ConversationId c) -> "conversations/" + c.value() + "/messages")
 *         .key(Message::sequenceNum)
 *         .id(m -> m.id().value())
 *         .build(FileRepo.open(dataDir, namespace), objectMapper);
 *
 * messages.append(conversation, seq -> Message.of(…, (int) seq));   // the next key, handed out under the lock
 * messages.page(conversation, 0, 50);
 * messages.update(conversation, id, m -> m.withTokenCount(42));
 * messages.deleteKeyRange(conversation, 10, 15);
 * }</pre>
 *
 * <p><b>The index.</b> What files a directory holds — keys, ids, the highest key
 * ever handed out — lives in memory beside the {@link FileRepo}, shared by
 * every log on that directory in the JVM. It is read from the file names once,
 * under the directory's write lock, and every write publishes the next version.
 * Appending therefore lists nothing, a page reads only its own records, and
 * finding a record by id opens exactly one file. A long log is not held in
 * memory, only its names.
 *
 * <p><b>Reading</b> takes no lock once the index is there, and never sees a
 * half-written record. <b>Writing</b> — append, put, update, delete — holds the
 * directory's write lock ({@link PathLocks}): records of one parent are written
 * one at a time, different parents in parallel.
 *
 * <p><b>Keys are never handed out twice.</b> When the records holding the
 * highest key are deleted, that key is kept in {@value LogState#SEQUENCE_FILE}
 * beside them, so the next append continues above it — also after a restart.
 *
 * <p>The function given to {@link #append} and {@link #update} runs under the
 * lock: build the record, nothing else.
 */
public class RecordLog<P, R> {

    private final FileRepo repo;
    private final Function<P, String> dir;
    private final ToLongFunction<R> key;
    private final Function<R, String> id;
    private final int keyWidth;
    private final ObjectReader reader;
    private final ObjectWriter writer;
    private final Duration lockTimeout;

    private RecordLog(Builder<P, R> b, FileRepo repo, ObjectMapper mapper) {
        this.repo = repo;
        this.dir = b.dir;
        this.key = b.key;
        this.id = b.id;
        this.keyWidth = b.keyWidth;
        this.reader = mapper.readerFor(b.type);
        ObjectWriter w = mapper.writerFor(b.type);
        this.writer = b.prettyPrint ? w.withDefaultPrettyPrinter() : w;
        this.lockTimeout = b.lockTimeout;
    }

    public static <R> Builder<Object, R> of(Class<R> type) {
        return new Builder<>(type);
    }

    /** The directory holding the records of {@code parent}. */
    public Path dirOf(P parent) {
        return repo.resolve(dir.apply(parent));
    }

    // ── reading ─────────────────────────────────────────────────────────────

    /** Up to {@code limit} records in key order, skipping the first {@code offset}. */
    public List<R> page(P parent, long offset, int limit) {
        if (offset < 0 || limit < 0) {
            throw new IllegalArgumentException("offset and limit must not be negative");
        }
        Path d = dirOf(parent);
        List<LogState.Entry> entries = state(d).entries();
        int from = (int) Math.min(offset, entries.size());
        int to = (int) Math.min((long) from + limit, entries.size());
        return read(d, entries.subList(from, to));
    }

    /** Every record, in key order. */
    public List<R> all(P parent) {
        Path d = dirOf(parent);
        return read(d, state(d).entries());
    }

    public Optional<R> find(P parent, String recordId) {
        Path d = dirOf(parent);
        LogState.Entry entry = state(d).byId(recordId);
        return entry == null ? Optional.empty() : read(d.resolve(entry.fileName()));
    }

    public int count(P parent) {
        return state(dirOf(parent)).entries().size();
    }

    // ── writing ─────────────────────────────────────────────────────────────

    /**
     * Stores the record {@code create} builds for the next key — one above the
     * highest ever handed out in this log. Two appends never get the same key.
     *
     * @throws IllegalStateException when the record does not carry the key it was handed
     */
    public R append(P parent, LongFunction<R> create) {
        Path d = dirOf(parent);
        return locked(d, () -> {
            LogState state = loaded(d);
            long next = state.highWater() + 1;
            R record = Objects.requireNonNull(create.apply(next), "append: the record was null");
            if (key.applyAsLong(record) != next) {
                throw new IllegalStateException("append: the record must carry the key it was handed ("
                        + next + "), it carries " + key.applyAsLong(record));
            }
            store(d, state, record);
            return record;
        });
    }

    /**
     * Stores {@code record} under its own key, replacing the record of the same
     * id — for importing, and for records whose key is their own (a summary's
     * first message, say). To change a stored record, use {@link #update}.
     */
    public R put(P parent, R record) {
        Objects.requireNonNull(record, "record");
        Path d = dirOf(parent);
        return locked(d, () -> {
            store(d, loaded(d), record);
            return record;
        });
    }

    /**
     * Reads the record, applies {@code change} and writes the result under the
     * directory's lock. Returning the given instance writes nothing.
     *
     * @return the changed record, or empty when there is none with this id
     * @throws IllegalStateException when the change alters the record's key or id
     */
    public Optional<R> update(P parent, String recordId, UnaryOperator<R> change) {
        Path d = dirOf(parent);
        return locked(d, () -> {
            LogState.Entry entry = loaded(d).byId(recordId);
            if (entry == null) {
                return Optional.<R>empty();
            }
            Path file = d.resolve(entry.fileName());
            Optional<R> current = read(file);
            if (current.isEmpty()) {
                return Optional.<R>empty();
            }
            R changed = Objects.requireNonNull(change.apply(current.get()), "update: the change returned null");
            if (changed != current.get()) {
                if (key.applyAsLong(changed) != entry.key() || !entry.id().equals(id.apply(changed))) {
                    throw new IllegalStateException("update must not change the key or id of " + file);
                }
                FileWrites.write(file, out -> writer.writeValue(out, changed));
            }
            return Optional.of(changed);
        });
    }

    /** Deletes the records whose key lies in [{@code fromKey}, {@code toKey}]; returns how many. */
    public int deleteKeyRange(P parent, long fromKey, long toKey) {
        Path d = dirOf(parent);
        return locked(d, () -> {
            LogState state = loaded(d);
            return remove(d, state, state.entries().stream()
                    .filter(e -> e.key() >= fromKey && e.key() <= toKey)
                    .toList());
        });
    }

    /** Deletes all but the {@code keep} records with the highest keys; returns how many went. */
    public int retainLast(P parent, int keep) {
        Path d = dirOf(parent);
        return locked(d, () -> {
            LogState state = loaded(d);
            List<LogState.Entry> entries = state.entries();
            return remove(d, state, entries.subList(0, Math.max(0, entries.size() - Math.max(0, keep))));
        });
    }

    /** Deletes every record of {@code parent}; returns how many. */
    public int deleteAll(P parent) {
        Path d = dirOf(parent);
        return locked(d, () -> {
            LogState state = loaded(d);
            return remove(d, state, state.entries());
        });
    }

    // ── index and files ─────────────────────────────────────────────────────

    /**
     * The index for reading. Loaded under the directory's lock the first time —
     * unless this thread is inside a write already, where taking a second lock is
     * refused; then the directory is listed for this one read.
     */
    private LogState state(Path d) {
        LogState state = repo.logState(d);
        if (state != null) {
            return state;
        }
        if (PathLocks.isHeldByCurrentThread()) {
            try {
                return LogState.load(d);
            } catch (IOException e) {
                throw new FileRepoException("Cannot list " + d, e);
            }
        }
        return locked(d, () -> loaded(d));
    }

    /** The index, loading and publishing it if needed. The caller holds the directory's lock. */
    private LogState loaded(Path d) throws IOException {
        LogState state = repo.logState(d);
        if (state == null) {
            state = LogState.load(d);
            repo.putLogState(d, state);
        }
        return state;
    }

    private void store(Path d, LogState state, R record) throws IOException {
        long k = key.applyAsLong(record);
        String recordId = id.apply(record);
        String name = fileName(k, recordId);
        FileWrites.write(d.resolve(name), out -> writer.writeValue(out, record));
        LogState.Entry previous = state.byId(recordId);
        if (previous != null && !previous.fileName().equals(name)) {
            Files.deleteIfExists(d.resolve(previous.fileName()));
        }
        repo.putLogState(d, state.with(new LogState.Entry(k, recordId, name)));
    }

    private int remove(Path d, LogState state, List<LogState.Entry> gone) throws IOException {
        if (gone.isEmpty()) {
            return 0;
        }
        if (gone.stream().anyMatch(e -> e.key() == state.highWater())) {
            // Written before the files go: a crash in between still remembers the key.
            FileWrites.writeString(d.resolve(LogState.SEQUENCE_FILE), Long.toString(state.highWater()));
        }
        for (LogState.Entry e : gone) {
            Files.deleteIfExists(d.resolve(e.fileName()));
        }
        repo.putLogState(d, state.without(gone));
        return gone.size();
    }

    private String fileName(long k, String recordId) {
        if (k < 0) {
            throw new IllegalArgumentException("A record key must not be negative: " + k);
        }
        String digits = Long.toString(k);
        if (digits.length() > keyWidth) {
            throw new IllegalArgumentException("Key " + k + " is wider than " + keyWidth + " digits");
        }
        if (recordId == null || recordId.isEmpty() || recordId.contains("/") || recordId.contains("\\")
                || recordId.startsWith(".")) {
            throw new IllegalArgumentException("A record id must be usable in a file name: " + recordId);
        }
        return "0".repeat(keyWidth - digits.length()) + digits + "_" + recordId + ".json";
    }

    private List<R> read(Path d, List<LogState.Entry> entries) {
        List<R> records = new ArrayList<>(entries.size());
        for (LogState.Entry e : entries) {
            read(d.resolve(e.fileName())).ifPresent(records::add);
        }
        return records;
    }

    /** A record deleted meanwhile is empty; an unreadable one throws. */
    private Optional<R> read(Path file) {
        R value;
        try (InputStream in = Files.newInputStream(file)) {
            value = reader.readValue(in);
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new FileRepoException("Cannot read record " + file, e);
        }
        if (value == null) {
            throw new FileRepoException("Record " + file + " contains null");
        }
        return Optional.of(value);
    }

    private <T> T locked(Path d, PathLocks.Action<T> action) {
        try {
            return PathLocks.withLock(d, lockTimeout, action);
        } catch (IOException e) {
            throw new FileRepoException("Cannot write records in " + d, e);
        }
    }

    // ── builder ─────────────────────────────────────────────────────────────

    public static class Builder<P, R> {

        private final Class<R> type;
        private Function<P, String> dir;
        private ToLongFunction<R> key;
        private Function<R, String> id;
        private int keyWidth = 10;
        private Duration lockTimeout = PathLocks.DEFAULT_TIMEOUT;
        private boolean prettyPrint;

        private Builder(Class<R> type) {
            this.type = type;
        }

        /**
         * The directory of a parent's records, relative to the data directory. Give
         * the lambda parameter its type: {@code .dir((ConversationId c) -> …)}.
         */
        @SuppressWarnings("unchecked")
        public <P2> Builder<P2, R> dir(Function<P2, String> dir) {
            Builder<P2, R> self = (Builder<P2, R>) (Builder<?, R>) this;
            self.dir = Objects.requireNonNull(dir, "dir");
            return self;
        }

        /** The record's key: its position in the log, the number in front of its file name. */
        public Builder<P, R> key(ToLongFunction<R> key) {
            this.key = Objects.requireNonNull(key, "key");
            return this;
        }

        /** The record's id, unique within its parent; part of its file name. */
        public Builder<P, R> id(Function<R, String> id) {
            this.id = Objects.requireNonNull(id, "id");
            return this;
        }

        /** How many digits the key is padded to in the file name; 10 by default. */
        public Builder<P, R> keyWidth(int keyWidth) {
            if (keyWidth < 1 || keyWidth > 19) throw new IllegalArgumentException("keyWidth must be 1..19");
            this.keyWidth = keyWidth;
            return this;
        }

        public Builder<P, R> lockTimeout(Duration lockTimeout) {
            this.lockTimeout = Objects.requireNonNull(lockTimeout, "lockTimeout");
            return this;
        }

        /** Indented JSON, for records people read by hand. */
        public Builder<P, R> prettyPrint() {
            this.prettyPrint = true;
            return this;
        }

        public RecordLog<P, R> build(FileRepo repo, ObjectMapper mapper) {
            if (dir == null) throw new IllegalStateException("dir() is required");
            if (key == null) throw new IllegalStateException("key() is required");
            if (id == null) throw new IllegalStateException("id() is required");
            return new RecordLog<>(this, Objects.requireNonNull(repo, "repo"), Objects.requireNonNull(mapper, "mapper"));
        }
    }
}
