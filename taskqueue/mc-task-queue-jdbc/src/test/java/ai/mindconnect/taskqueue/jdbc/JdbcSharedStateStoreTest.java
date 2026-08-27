package ai.mindconnect.taskqueue.jdbc;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Same reachability convention as {@link JdbcTaskStoreTest}. */
class JdbcSharedStateStoreTest {

    private static final String URL = System.getenv().getOrDefault(
            "MC_TASKQUEUE_TEST_URL", "jdbc:postgresql://localhost:5433/postgres");
    private static final String USER = System.getenv().getOrDefault("MC_TASKQUEUE_TEST_USER", "postgres");
    private static final String PASSWORD = System.getenv().getOrDefault("MC_TASKQUEUE_TEST_PASSWORD", "test");

    private JdbcSharedStateStore shared;

    @BeforeAll
    static void requiresPostgres() {
        try (var c = DriverManager.getConnection(URL, USER, PASSWORD)) {
            assumeTrue(c.isValid(2));
        } catch (Exception e) {
            assumeTrue(false, "no Postgres reachable — skipping");
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        var ds = new PGSimpleDataSource();
        ds.setUrl(URL);
        ds.setUser(USER);
        ds.setPassword(PASSWORD);
        try (var c = ds.getConnection(); var s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS mc_shared_state");
        }
        shared = new JdbcSharedStateStore(ds).initSchema();
    }

    @Test
    void exactlyOneConcurrentClaimWins() throws Exception {
        int racers = 32;
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(racers);
        var winners = new AtomicInteger();
        for (int i = 0; i < racers; i++) {
            int racer = i;
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    if (shared.putIfAbsent("crawl-1", "https://contested", "task-" + racer)) {
                        winners.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await();
        assertThat(winners.get()).isEqualTo(1);
    }

    @Test
    void putGetAllClearRoundTrip() {
        assertThat(shared.putIfAbsent("crawl-1", "a", List.of(1, 2))).isTrue();
        shared.put("crawl-1", "a", "overwritten");
        shared.put("crawl-2", "a", "other scope");

        assertThat(shared.get("crawl-1", "a")).contains("overwritten");
        assertThat(shared.all("crawl-1")).containsOnlyKeys("a");
        assertThat(shared.clear("crawl-1")).isEqualTo(1);
        assertThat(shared.get("crawl-1", "a")).isEmpty();
        assertThat(shared.get("crawl-2", "a")).contains("other scope");
    }
}
