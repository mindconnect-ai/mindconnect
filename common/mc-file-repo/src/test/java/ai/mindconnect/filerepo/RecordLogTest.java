package ai.mindconnect.filerepo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecordLogTest {

    record Line(String id, long seq, String text) {
        Line withText(String text) {
            return new Line(id, seq, text);
        }
    }

    record Counter(String id, int value) { }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path dir;

    private FileRepo repo;
    private RecordLog<String, Line> lines;

    @BeforeEach
    void setUp() {
        repo = FileRepo.open(dir.resolve("data"), "ns");
        lines = log(repo);
    }

    private static RecordLog<String, Line> log(FileRepo repo) {
        return RecordLog.of(Line.class)
                .dir((String conversation) -> "conversations/" + conversation + "/lines")
                .key(Line::seq)
                .id(Line::id)
                .lockTimeout(Duration.ofMinutes(1))
                .build(repo, MAPPER);
    }

    private Line append(String conversation, String text) {
        return lines.append(conversation, seq -> new Line("id-" + seq, seq, text));
    }

    @Test
    void appendNumbersTheRecordsAndPagesReadOnlyTheirOwn() {
        for (int i = 1; i <= 5; i++) append("c", "line " + i);

        assertThat(lines.count("c")).isEqualTo(5);
        assertThat(lines.page("c", 1, 2)).extracting(Line::seq).containsExactly(2L, 3L);
        assertThat(lines.page("c", 4, 10)).extracting(Line::seq).containsExactly(5L);
        assertThat(lines.page("c", 9, 10)).isEmpty();
        assertThat(lines.find("c", "id-3")).map(Line::text).contains("line 3");
        assertThat(lines.find("c", "nope")).isEmpty();
        assertThat(lines.all("other")).isEmpty();
        assertThat(lines.dirOf("c").resolve("0000000003_id-3.json")).exists();
    }

    @Test
    void concurrentAppendsThroughTwoLogsGetDistinctKeys() throws Exception {
        RecordLog<String, Line> second = log(FileRepo.open(dir.resolve("./data"), "ns"));

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 400; i++) {
                RecordLog<String, Line> log = i % 2 == 0 ? lines : second;
                futures.add(pool.submit(() -> log.append("c", seq -> new Line("id-" + seq, seq, "x"))));
            }
            for (Future<?> future : futures) future.get();
        }

        assertThat(lines.all("c")).extracting(Line::seq)
                .containsExactlyElementsOf(LongStream.rangeClosed(1, 400).boxed().toList());
    }

    @Test
    void appendRefusesARecordThatIgnoresItsKey() {
        assertThatThrownBy(() -> lines.append("c", seq -> new Line("x", seq + 1, "wrong")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(lines.count("c")).isZero();
    }

    @Test
    void concurrentUpdatesOfOneRecordAllLandAndTheKeyStays() throws Exception {
        Line line = append("c", "");

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                futures.add(pool.submit(() -> lines.update("c", line.id(), l -> l.withText(l.text() + "."))));
            }
            for (Future<?> future : futures) future.get();
        }

        assertThat(lines.find("c", line.id())).map(Line::text).contains(".".repeat(200));
        assertThatThrownBy(() -> lines.update("c", line.id(), l -> new Line(l.id(), 99, l.text())))
                .isInstanceOf(IllegalStateException.class);
        assertThat(lines.update("c", "nope", l -> l.withText("never"))).isEmpty();
    }

    @Test
    void aDeletedTailIsNotNumberedAgainNotEvenAfterARestart() {
        for (int i = 1; i <= 5; i++) append("c", "line " + i);

        assertThat(lines.deleteKeyRange("c", 4, 5)).isEqualTo(2);
        assertThat(append("c", "after delete").seq()).isEqualTo(6);

        assertThat(lines.deleteKeyRange("c", 6, 6)).isEqualTo(1);
        repo.forgetLogStates();
        assertThat(append("c", "after restart").seq()).isEqualTo(7);
        assertThat(lines.all("c")).extracting(Line::seq).containsExactly(1L, 2L, 3L, 7L);
    }

    @Test
    void putStoresUnderTheRecordsOwnKeyAndMovesARecordWhoseKeyChanged() {
        lines.put("c", new Line("a", 3, "first"));
        lines.put("c", new Line("a", 5, "moved"));

        assertThat(lines.all("c")).containsExactly(new Line("a", 5, "moved"));
        assertThat(lines.dirOf("c").resolve("0000000003_a.json")).doesNotExist();
        assertThat(append("c", "next").seq()).isEqualTo(6);
    }

    @Test
    void retainLastAndDeleteAllKeepTheNumbering() {
        for (int i = 1; i <= 5; i++) append("c", "line " + i);

        assertThat(lines.retainLast("c", 2)).isEqualTo(3);
        assertThat(lines.all("c")).extracting(Line::seq).containsExactly(4L, 5L);
        assertThat(lines.deleteAll("c")).isEqualTo(2);
        assertThat(append("c", "again").seq()).isEqualTo(6);
    }

    @Test
    void recordsWrittenBeforeAreFoundAndOtherFilesIgnored() throws Exception {
        Path d = lines.dirOf("c");
        Files.createDirectories(d);
        Files.writeString(d.resolve("0000000002_old.json"), MAPPER.writeValueAsString(new Line("old", 2, "before")));
        Files.writeString(d.resolve(".0000000003_x.json.123.tmp"), "{");
        Files.writeString(d.resolve("readme.txt"), "not a record");

        assertThat(lines.all("c")).containsExactly(new Line("old", 2, "before"));
        assertThat(append("c", "new").seq()).isEqualTo(3);
    }

    @Test
    void anUnreadableRecordThrows() throws Exception {
        Line line = append("c", "fine");
        Files.writeString(lines.dirOf("c").resolve("0000000001_" + line.id() + ".json"), "{\"id\":");

        assertThatThrownBy(() -> lines.all("c")).isInstanceOf(FileRepoException.class);
    }

    @Test
    void readingALogInsideAnotherWriteWorks() {
        Documents<String, Counter> counters = Documents.of(Counter.class)
                .path((String id) -> "counters/" + id + ".json")
                .build(repo, MAPPER);
        counters.create("n", new Counter("n", 0));
        append("c", "one");
        append("c", "two");
        repo.forgetLogStates();

        assertThat(counters.update("n", c -> new Counter("n", lines.count("c"))))
                .contains(new Counter("n", 2));
    }
}
