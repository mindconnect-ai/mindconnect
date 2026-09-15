package ai.mindconnect.namespace.adapter.pg;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.jdbc.Sql;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Against the local Postgres (see {@link TestDb}); skipped without one. */
class PgNamespacePurgeTest {

    private Sql sql;

    @BeforeEach
    void setUp() {
        sql = Sql.of(TestDb.require());
        sql.execute("CREATE TABLE IF NOT EXISTS mc_purge_test_ns (namespace TEXT NOT NULL, id TEXT NOT NULL, PRIMARY KEY (namespace, id))");
        sql.execute("CREATE TABLE IF NOT EXISTS mc_purge_test_wf (partition_key TEXT NOT NULL, id TEXT NOT NULL, PRIMARY KEY (partition_key, id))");
        sql.execute("CREATE TABLE IF NOT EXISTS vs_purgetest__docs (id TEXT PRIMARY KEY)");
        sql.execute("CREATE TABLE IF NOT EXISTS vs_purgetest_other__docs (id TEXT PRIMARY KEY)");
        sql.update("INSERT INTO mc_purge_test_ns VALUES ('purgetest', 'a'), ('purgetest', 'b'), ('other', 'c')");
        sql.update("INSERT INTO mc_purge_test_wf VALUES ('purgetest', 'w1'), ('other', 'w2')");
    }

    @AfterEach
    void tearDown() {
        for (String table : new String[]{"mc_purge_test_ns", "mc_purge_test_wf", "vs_purgetest__docs", "vs_purgetest_other__docs"}) {
            sql.execute("DROP TABLE IF EXISTS " + table);
        }
    }

    @Test
    void removesTheNamespacesRowsFromEveryNamespacedTableAndDropsItsVectorStoreTables() {
        new PgNamespacePurge(sql).purge(new Namespace("purgetest"));

        assertThat(sql.scalar("SELECT count(*) FROM mc_purge_test_ns WHERE namespace = 'purgetest'", Long.class)).isZero();
        assertThat(sql.scalar("SELECT count(*) FROM mc_purge_test_ns", Long.class)).isEqualTo(1L);
        assertThat(sql.scalar("SELECT count(*) FROM mc_purge_test_wf", Long.class)).isEqualTo(1L);
        assertThat(sql.scalar("SELECT count(*) FROM information_schema.tables WHERE table_name = 'vs_purgetest__docs'", Long.class)).isZero();
        // the prefix ends in a double underscore: a namespace whose name merely starts the same keeps its tables
        assertThat(sql.scalar("SELECT count(*) FROM information_schema.tables WHERE table_name = 'vs_purgetest_other__docs'", Long.class)).isEqualTo(1L);
    }
}
