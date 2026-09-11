package ai.mindconnect.filerepo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * What a {@link RecordLog} knows about one directory without opening a record:
 * the record files in key order, which file holds which id, and the highest key
 * ever handed out. Immutable — a writer publishes a new state, a reader keeps
 * the one it took.
 */
final class LogState {

    /** The file that keeps the highest key once the records holding it are deleted. */
    static final String SEQUENCE_FILE = ".sequence";

    private static final Pattern RECORD_FILE = Pattern.compile("(\\d+)_(.+)\\.json");

    record Entry(long key, String id, String fileName) { }

    private final List<Entry> entries;
    private final Map<String, Entry> byId;
    private final long highWater;

    private LogState(List<Entry> entries, long highWater) {
        this.entries = List.copyOf(entries);
        this.byId = new HashMap<>();
        for (Entry e : this.entries) byId.put(e.id(), e);
        this.highWater = highWater;
    }

    /** Lists the directory; a directory that does not exist yet is an empty log. */
    static LogState load(Path dir) throws IOException {
        List<Entry> entries = new ArrayList<>();
        long highWater = 0;
        List<Path> files;
        try (Stream<Path> listing = Files.list(dir)) {
            files = listing.toList();
        } catch (NoSuchFileException e) {
            files = List.of();
        }
        for (Path file : files) {
            Entry entry = parse(file.getFileName().toString());
            if (entry != null) {
                entries.add(entry);
                highWater = Math.max(highWater, entry.key());
            }
        }
        Path sequence = dir.resolve(SEQUENCE_FILE);
        try {
            highWater = Math.max(highWater, Long.parseLong(Files.readString(sequence).trim()));
        } catch (NoSuchFileException e) {
            // no deleted tail to remember
        } catch (NumberFormatException e) {
            throw new FileRepoException("Unreadable " + sequence, e);
        }
        entries.sort(Comparator.comparing(Entry::fileName));
        return new LogState(entries, highWater);
    }

    /** The entry a record file name stands for, or null for any other file — temporary files included. */
    static Entry parse(String fileName) {
        if (fileName.startsWith(".")) return null;
        Matcher m = RECORD_FILE.matcher(fileName);
        if (!m.matches()) return null;
        try {
            return new Entry(Long.parseLong(m.group(1)), m.group(2), fileName);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    List<Entry> entries() {
        return entries;
    }

    Entry byId(String id) {
        return byId.get(id);
    }

    long highWater() {
        return highWater;
    }

    /** This state with {@code entry} in it, replacing an earlier entry of the same id. */
    LogState with(Entry entry) {
        List<Entry> next = new ArrayList<>(entries.size() + 1);
        for (Entry e : entries) {
            if (!e.id().equals(entry.id())) next.add(e);
        }
        int at = 0;
        while (at < next.size() && next.get(at).fileName().compareTo(entry.fileName()) < 0) at++;
        next.add(at, entry);
        return new LogState(next, Math.max(highWater, entry.key()));
    }

    /** This state without {@code removed}; the highest key stays where it was. */
    LogState without(Collection<Entry> removed) {
        Set<String> gone = new HashSet<>();
        for (Entry e : removed) gone.add(e.fileName());
        List<Entry> next = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            if (!gone.contains(e.fileName())) next.add(e);
        }
        return new LogState(next, highWater);
    }
}
