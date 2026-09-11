package ai.mindconnect.filerepo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DocumentsTest {

    record Counter(String id, int value) {
        Counter increment() {
            return new Counter(id, value + 1);
        }
    }

    record Note(String id, String text) { }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path dir;

    private FileRepo repo;
    private Documents<String, Counter> counters;

    @BeforeEach
    void setUp() {
        repo = FileRepo.open(dir.resolve("data"), "ns");
        counters = counters(repo);
    }

    private static Documents<String, Counter> counters(FileRepo repo) {
        return Documents.of(Counter.class)
                .path((String id) -> "counters/" + id + ".json")
                .lockTimeout(Duration.ofMinutes(1))
                .build(repo, MAPPER);
    }

    @Test
    void createsFindsUpdatesAndDeletes() {
        counters.create("a", new Counter("a", 0));
        assertThat(counters.find("a")).contains(new Counter("a", 0));

        assertThat(counters.update("a", Counter::increment)).contains(new Counter("a", 1));
        assertThat(counters.find("a")).contains(new Counter("a", 1));

        assertThat(counters.delete("a")).isTrue();
        assertThat(counters.find("a")).isEmpty();
        assertThat(counters.delete("a")).isFalse();
    }

    @Test
    void createRefusesAnExistingDocument() {
        counters.create("a", new Counter("a", 0));

        assertThatThrownBy(() -> counters.create("a", new Counter("a", 7)))
                .isInstanceOf(DocumentExistsException.class);
        assertThat(counters.find("a")).contains(new Counter("a", 0));
    }

    @Test
    void createIfAbsentBuildsOnlyTheFirstDocument() {
        assertThat(counters.createIfAbsent("a", () -> new Counter("a", 1))).isEqualTo(new Counter("a", 1));
        assertThat(counters.createIfAbsent("a", () -> {
            throw new AssertionError("must not build a second document");
        })).isEqualTo(new Counter("a", 1));
    }

    @Test
    void updatingAMissingDocumentWritesNothing() {
        assertThat(counters.update("missing", Counter::increment)).isEmpty();
        assertThat(counters.exists("missing")).isFalse();
    }

    @Test
    void anUpdateThatReturnsTheSameInstanceWritesNothing() throws Exception {
        counters.put("a", new Counter("a", 0));
        Path file = counters.pathOf("a");
        Object before = fileKey(file);
        assumeTrue(before != null, "the file system reports no file keys");

        counters.update("a", c -> c);
        assertThat(fileKey(file)).isEqualTo(before);

        counters.update("a", Counter::increment);
        assertThat(fileKey(file)).isNotEqualTo(before);
    }

    @Test
    void anUnreadableDocumentThrowsInsteadOfPassingForMissing() throws Exception {
        Path file = counters.pathOf("broken");
        Files.createDirectories(file.getParent());

        Files.writeString(file, "");
        assertThatThrownBy(() -> counters.find("broken"))
                .isInstanceOf(FileRepoException.class)
                .hasMessageContaining("broken.json");

        Files.writeString(file, "{\"id\":");
        assertThatThrownBy(() -> counters.find("broken")).isInstanceOf(FileRepoException.class);
        assertThatThrownBy(() -> counters.update("broken", Counter::increment)).isInstanceOf(FileRepoException.class);
        assertThat(Files.readString(file)).isEqualTo("{\"id\":");
    }

    @Test
    void findAllReadsDocumentsByNameAndSkipsEverythingElse() throws Exception {
        counters.put("b", new Counter("b", 2));
        counters.put("a", new Counter("a", 1));
        Path counterDir = repo.resolve("counters");
        Files.writeString(counterDir.resolve(".a.json.4711.tmp"), "{");
        Files.writeString(counterDir.resolve("readme.txt"), "not a document");

        assertThat(counters.findAll("counters")).containsExactly(new Counter("a", 1), new Counter("b", 2));
        assertThat(counters.findAll("nothing-here")).isEmpty();
    }

    @Test
    void findAllReadsOneDocumentPerSubdirectory() throws Exception {
        Documents<String, Counter> perSession = Documents.of(Counter.class)
                .path((String id) -> "sessions/" + id + "/counter.json")
                .build(repo, MAPPER);
        perSession.put("s2", new Counter("s2", 2));
        perSession.put("s1", new Counter("s1", 1));
        Files.createDirectories(repo.resolve("sessions/without-document"));

        assertThat(perSession.findAll("sessions", "counter.json"))
                .containsExactly(new Counter("s1", 1), new Counter("s2", 2));
    }

    @Test
    void concurrentUpdatesAllLandEvenThroughSeparateInstances() throws Exception {
        counters.create("shared", new Counter("shared", 0));
        Documents<String, Counter> second = counters(FileRepo.open(dir.resolve("./data"), "ns"));

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 1000; i++) {
                Documents<String, Counter> docs = i % 2 == 0 ? counters : second;
                futures.add(pool.submit(() -> docs.update("shared", Counter::increment)));
            }
            for (Future<?> future : futures) future.get();
        }

        assertThat(counters.find("shared")).contains(new Counter("shared", 1000));
    }

    @Test
    void readersNeverSeeAHalfWrittenDocument() throws Exception {
        Documents<String, Note> notes = Documents.of(Note.class)
                .path((String id) -> "notes/" + id + ".json")
                .build(repo, MAPPER);
        String a = "a".repeat(64_000);
        String b = "b".repeat(64_000);
        notes.put("n", new Note("n", a));
        AtomicBoolean done = new AtomicBoolean();
        AtomicInteger reads = new AtomicInteger();
        List<Throwable> failures = new CopyOnWriteArrayList<>();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int r = 0; r < 4; r++) {
                pool.submit(() -> {
                    while (!done.get()) {
                        try {
                            String text = notes.find("n").orElseThrow().text();
                            if (!text.equals(a) && !text.equals(b)) {
                                failures.add(new AssertionError("read " + text.length() + " chars"));
                            }
                            reads.incrementAndGet();
                        } catch (Throwable t) {
                            failures.add(t);
                            return;
                        }
                    }
                });
            }
            try {
                for (int i = 0; i < 300; i++) {
                    notes.put("n", new Note("n", i % 2 == 0 ? b : a));
                }
            } finally {
                done.set(true);
            }
        }

        assertThat(failures).isEmpty();
        assertThat(reads.get()).isPositive();
    }

    @Test
    void writingInsideAnUpdateIsRefusedAndChangesNothing() {
        counters.create("a", new Counter("a", 0));

        assertThatThrownBy(() -> counters.update("a", c -> {
            counters.put("b", new Counter("b", 0));
            return c.increment();
        })).isInstanceOf(NestedWriteException.class);

        assertThat(counters.find("a")).contains(new Counter("a", 0));
        assertThat(counters.exists("b")).isFalse();
        assertThat(counters.update("a", c -> new Counter("a", c.value() + 1 + counters.find("a").orElseThrow().value())))
                .as("reading inside an update is fine, and the lock was released")
                .contains(new Counter("a", 1));
    }

    @Test
    void aKeyThatLeadsOutOfTheDirectoryIsRefused() {
        assertThatThrownBy(() -> counters.put("../../escape", new Counter("x", 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(dir.resolve("data/escape.json")).doesNotExist();
    }

    @Test
    void prettyPrintIndents() throws Exception {
        Documents<String, Counter> pretty = Documents.of(Counter.class)
                .path((String id) -> "pretty/" + id + ".json")
                .prettyPrint()
                .build(repo, MAPPER);

        pretty.put("a", new Counter("a", 1));

        assertThat(Files.readString(pretty.pathOf("a"))).contains("\n");
        assertThat(pretty.find("a")).contains(new Counter("a", 1));
    }

    private static Object fileKey(Path file) throws Exception {
        return Files.readAttributes(file, BasicFileAttributes.class).fileKey();
    }
}
