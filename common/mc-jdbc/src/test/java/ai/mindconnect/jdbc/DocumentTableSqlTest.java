package ai.mindconnect.jdbc;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The SQL a table generates — no database needed. */
class DocumentTableSqlTest {

    record Thing(UUID id, String namespace, String name, int rank) { }

    private final DocumentTable<Thing> things = DocumentTable.of(Thing.class)
            .table("mc_thing")
            .id("id", "UUID", Thing::id)
            .requiredColumn("namespace", "TEXT", Thing::namespace)
            .column("name", "TEXT", Thing::name)
            .index("namespace", "name")
            .uniqueIndex("name")
            .build(Sql.of(null));

    @Test
    void ddlCreatesTableAddsColumnsAndIndexes() {
        String ddl = things.ddl();
        assertThat(ddl).isEqualTo("""
                CREATE TABLE IF NOT EXISTS mc_thing (
                    id UUID PRIMARY KEY,
                    namespace TEXT NOT NULL,
                    name TEXT,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    doc JSONB NOT NULL
                );
                ALTER TABLE mc_thing ADD COLUMN IF NOT EXISTS namespace TEXT;
                ALTER TABLE mc_thing ADD COLUMN IF NOT EXISTS name TEXT;
                CREATE INDEX IF NOT EXISTS mc_thing_namespace_name_idx ON mc_thing (namespace, name);
                CREATE UNIQUE INDEX IF NOT EXISTS mc_thing_name_idx ON mc_thing (name);
                """);
    }

    private final DocumentTable<Thing> tenantThings = DocumentTable.of(Thing.class)
            .table("mc_tenant_thing")
            .partitionKey("namespace", "TEXT", Thing::namespace)
            .id("id", "TEXT", t -> t.id().toString())
            .column("name", "TEXT", Thing::name)
            .build(Sql.of(null));

    @Test
    void aPartitionKeyMakesTheKeyAPair() {
        assertThat(tenantThings.ddl()).isEqualTo("""
                CREATE TABLE IF NOT EXISTS mc_tenant_thing (
                    namespace TEXT NOT NULL,
                    id TEXT NOT NULL,
                    name TEXT,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    doc JSONB NOT NULL,
                    PRIMARY KEY (namespace, id)
                );
                ALTER TABLE mc_tenant_thing ADD COLUMN IF NOT EXISTS name TEXT;
                """);
        assertThat(tenantThings.upsertSql()).isEqualTo(
                "INSERT INTO mc_tenant_thing (namespace, id, name, updated_at, doc) VALUES (?, ?, ?, now(), ?) "
                + "ON CONFLICT (namespace, id) DO UPDATE SET name = EXCLUDED.name, "
                + "updated_at = now(), doc = EXCLUDED.doc");
        assertThat(tenantThings.columnList()).isEqualTo("namespace, id, name, updated_at");
    }

    @Test
    void theSingleAndPairedKeyFormsRefuseTheWrongTable() {
        assertThatThrownBy(() -> tenantThings.findById("x"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("namespace");
        assertThatThrownBy(() -> things.findById("acme", "x"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("single-column");
    }

    @Test
    void upsertReplacesColumnsAndDocumentOnConflict() {
        assertThat(things.upsertSql()).isEqualTo(
                "INSERT INTO mc_thing (id, namespace, name, updated_at, doc) VALUES (?, ?, ?, now(), ?) "
                + "ON CONFLICT (id) DO UPDATE SET namespace = EXCLUDED.namespace, name = EXCLUDED.name, "
                + "updated_at = now(), doc = EXCLUDED.doc");
    }

    @Test
    void aTableWithOnlyAnIdStillUpserts() {
        DocumentTable<Thing> bare = DocumentTable.of(Thing.class)
                .table("t").id("id", "UUID", Thing::id).build(Sql.of(null));
        assertThat(bare.upsertSql()).isEqualTo(
                "INSERT INTO t (id, updated_at, doc) VALUES (?, now(), ?) "
                + "ON CONFLICT (id) DO UPDATE SET updated_at = now(), doc = EXCLUDED.doc");
    }

    @Test
    void theProjectionListsIdColumnsAndUpdatedAtButNotTheDocument() {
        assertThat(things.columnList()).isEqualTo("id, namespace, name, updated_at");
    }

    @Test
    void deleteWithoutWhereIsRefused() {
        assertThatThrownBy(() -> things.delete("ORDER BY name"))
                .isInstanceOf(JdbcException.class)
                .hasMessageContaining("WHERE");
    }

    @Test
    void builderInsistsOnTableAndId() {
        assertThatThrownBy(() -> DocumentTable.of(Thing.class).id("id", "UUID", Thing::id).build(Sql.of(null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("table");
        assertThatThrownBy(() -> DocumentTable.of(Thing.class).table("t").build(Sql.of(null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("id");
    }
}
