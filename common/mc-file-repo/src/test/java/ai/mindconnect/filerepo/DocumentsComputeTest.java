package ai.mindconnect.filerepo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code compute}: decide on the stored document — or on its absence — and write, under one lock. */
class DocumentsComputeTest {

    record Counter(String id, int value) { }

    @TempDir
    Path dir;

    private Documents<String, Counter> counters() {
        return Documents.of(Counter.class)
                .path((String id) -> "counters/" + id + ".json")
                .lockTimeout(Duration.ofMinutes(1))
                .build(FileRepo.open(dir.resolve("data"), "ns"), new ObjectMapper());
    }

    @Test
    void computeCreatesChangesOrLeavesTheDocumentAlone() {
        Documents<String, Counter> counters = counters();

        assertThat(counters.compute("a", c -> c.map(x -> new Counter("a", x.value() + 1))
                .orElse(new Counter("a", 1)))).isEqualTo(new Counter("a", 1));
        assertThat(counters.compute("a", c -> new Counter("a", c.orElseThrow().value() + 1)))
                .isEqualTo(new Counter("a", 2));
        assertThat(counters.compute("a", c -> c.orElseThrow())).isEqualTo(new Counter("a", 2));
        assertThat(counters.find("a")).contains(new Counter("a", 2));
    }

    @Test
    void concurrentComputesStartingFromNothingAllLand() throws Exception {
        Documents<String, Counter> counters = counters();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 500; i++) {
                futures.add(pool.submit(() -> counters.compute("shared",
                        c -> new Counter("shared", c.map(Counter::value).orElse(0) + 1))));
            }
            for (Future<?> future : futures) future.get();
        }

        assertThat(counters.find("shared")).contains(new Counter("shared", 500));
    }
}
