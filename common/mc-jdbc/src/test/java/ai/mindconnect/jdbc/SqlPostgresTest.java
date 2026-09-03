package ai.mindconnect.jdbc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Binding, mapping and transactions against a real Postgres; skipped when none is reachable. */
class SqlPostgresTest {

    enum Status { OPEN, CLOSED }

    private Sql sql;

    @BeforeEach
    void setUp() {
        sql = Sql.of(TestDb.requirePostgres());
        sql.execute("""
                DROP TABLE IF EXISTS mc_jdbc_test_row;
                CREATE TABLE mc_jdbc_test_row (
                    id UUID PRIMARY KEY, name TEXT, status TEXT, n INTEGER,
                    at TIMESTAMPTZ, flag BOOLEAN, attrs JSONB)
                """);
    }

    @Test
    void everyTypeGoesInAndComesBackOut() {
        UUID id = UUID.randomUUID();
        Instant at = Instant.parse("2026-09-03T10:15:30.123456Z");
        Map<String, Object> attrs = Map.of("k", "v", "list", List.of(1, 2));

        int n = sql.update("INSERT INTO mc_jdbc_test_row VALUES (?,?,?,?,?,?,?)",
                id, "name", Status.OPEN, 7, at, true, sql.json().jsonb(attrs));
        assertThat(n).isEqualTo(1);

        var row = sql.queryOne("SELECT * FROM mc_jdbc_test_row WHERE id = ?", r -> List.of(
                r.uuid("id"), r.string("name"), r.enumValue("status", Status.class), r.integer("n"),
                r.instant("at"), r.bool("flag"), r.json("attrs", Map.class)), id).orElseThrow();
        assertThat(row).containsExactly(id, "name", Status.OPEN, 7, at, true, attrs);
    }

    @Test
    void bytesGoInAsByteaAndComeBackWhole() {
        sql.execute("CREATE TABLE IF NOT EXISTS mc_jdbc_test_blob (id UUID PRIMARY KEY, body BYTEA)");
        UUID id = UUID.randomUUID();
        byte[] body = new byte[] {0, 1, 2, (byte) 0xFF, 10, 13, 0};
        sql.update("INSERT INTO mc_jdbc_test_blob VALUES (?, ?)", id, body);
        assertThat(sql.queryOne("SELECT body FROM mc_jdbc_test_blob WHERE id = ?", r -> r.bytes("body"), id))
                .contains(body);
        sql.execute("DROP TABLE mc_jdbc_test_blob");
    }

    @Test
    void nullsAreNullsNotZeros() {
        UUID id = UUID.randomUUID();
        sql.update("INSERT INTO mc_jdbc_test_row (id, name, status, n, at, flag, attrs) VALUES (?,?,?,?,?,?,?)",
                id, null, null, null, null, null, null);

        var row = sql.queryOne("SELECT * FROM mc_jdbc_test_row WHERE id = ?", r -> new Object[] {
                r.string("name"), r.enumValue("status", Status.class), r.integer("n"),
                r.instant("at"), r.bool("flag"), r.json("attrs", Map.class)}, id).orElseThrow();
        assertThat(row).containsOnlyNulls();
    }

    @Test
    void queryOneRefusesTwoRows() {
        sql.update("INSERT INTO mc_jdbc_test_row (id, name) VALUES (?, ?)", UUID.randomUUID(), "x");
        sql.update("INSERT INTO mc_jdbc_test_row (id, name) VALUES (?, ?)", UUID.randomUUID(), "x");
        assertThatThrownBy(() -> sql.queryOne("SELECT id FROM mc_jdbc_test_row WHERE name = ?", r -> r.uuid("id"), "x"))
                .isInstanceOf(JdbcException.class).hasMessageContaining("at most one row");
        assertThat(sql.scalar("SELECT count(*) FROM mc_jdbc_test_row", Long.class)).isEqualTo(2L);
    }

    @Test
    void aFailedTransactionLeavesNothingBehind() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> sql.inTransaction(tx -> {
            tx.update("INSERT INTO mc_jdbc_test_row (id, name) VALUES (?, ?)", id, "first");
            tx.update("INSERT INTO mc_jdbc_test_row (id, name) VALUES (?, ?)", id, "duplicate key");
        })).isInstanceOf(JdbcException.class);
        assertThat(sql.scalar("SELECT count(*) FROM mc_jdbc_test_row", Long.class)).isZero();
    }

    @Test
    void aCommittedTransactionKeepsEverythingAndNestsIntoItself() {
        int total = sql.inTransaction(tx -> {
            tx.update("INSERT INTO mc_jdbc_test_row (id, name) VALUES (?, ?)", UUID.randomUUID(), "a");
            return tx.inTransaction(inner -> {
                inner.update("INSERT INTO mc_jdbc_test_row (id, name) VALUES (?, ?)", UUID.randomUUID(), "b");
                return inner.scalar("SELECT count(*) FROM mc_jdbc_test_row", Long.class).intValue();
            });
        });
        assertThat(total).isEqualTo(2);
        assertThat(sql.scalar("SELECT count(*) FROM mc_jdbc_test_row", Long.class)).isEqualTo(2L);
    }

    @Test
    void aJsonbOperatorThatLooksLikeAPlaceholderIsEscapedAsDoubleQuestionMark() {
        sql.update("INSERT INTO mc_jdbc_test_row (id, attrs) VALUES (?, ?)",
                UUID.randomUUID(), Jsonb.of("{\"tags\": [\"a\", \"b\"]}"));
        List<UUID> hit = sql.query("SELECT id FROM mc_jdbc_test_row WHERE attrs->'tags' ?? ?", r -> r.uuid("id"), "a");
        List<UUID> miss = sql.query("SELECT id FROM mc_jdbc_test_row WHERE attrs->'tags' ?? ?", r -> r.uuid("id"), "z");
        assertThat(hit).hasSize(1);
        assertThat(miss).isEmpty();
    }
}
