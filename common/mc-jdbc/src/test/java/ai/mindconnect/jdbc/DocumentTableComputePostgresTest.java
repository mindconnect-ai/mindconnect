package ai.mindconnect.jdbc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code compute} against a real Postgres; skipped when none is reachable. */
class DocumentTableComputePostgresTest {

    record Counter(String namespace, String id, int value) { }

    private DocumentTable<Counter> counters;

    @BeforeEach
    void setUp() {
        Sql sql = Sql.of(TestDb.requirePostgres());
        sql.execute("DROP TABLE IF EXISTS mc_jdbc_test_counter");
        counters = DocumentTable.of(Counter.class)
                .table("mc_jdbc_test_counter")
                .partitionKey("namespace", "TEXT", Counter::namespace)
                .id("id", "TEXT", Counter::id)
                .build(sql);
        counters.createSchema();
    }

    private static Counter next(Optional<Counter> current) {
        return new Counter("ns", "c", current.map(Counter::value).orElse(0) + 1);
    }

    @Test
    void computeInsertsAMissingRowAndUpdatesAnExistingOne() {
        assertThat(counters.compute("ns", "c", DocumentTableComputePostgresTest::next))
                .isEqualTo(new Counter("ns", "c", 1));
        assertThat(counters.compute("ns", "c", DocumentTableComputePostgresTest::next))
                .isEqualTo(new Counter("ns", "c", 2));
        assertThat(counters.findById("ns", "c")).contains(new Counter("ns", "c", 2));
    }

    @Test
    void concurrentComputesOnAMissingRowAllLand() throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                futures.add(pool.submit(() -> counters.compute("ns", "c", DocumentTableComputePostgresTest::next)));
            }
            for (Future<?> future : futures) future.get();
        }

        assertThat(counters.findById("ns", "c")).contains(new Counter("ns", "c", 50));
    }

    @Test
    void computeMustCreateTheRowItWasAskedFor() {
        assertThatThrownBy(() -> counters.compute("ns", "c", current -> new Counter("ns", "other", 1)))
                .isInstanceOf(JdbcException.class);
        assertThat(counters.findAll()).isEmpty();
    }
}
