package ai.mindconnect.jdbc;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Round trips against a real Postgres; skipped when none is reachable. */
class DocumentTablePostgresTest {

    enum Kind { CHAT, TOOL }

    record Thing(UUID id, String namespace, String name, Kind kind, Instant createdAt, List<String> tags) { }

    private Sql sql;
    private DocumentTable<Thing> things;

    @BeforeEach
    void setUp() {
        sql = Sql.of(TestDb.requirePostgres());
        sql.execute("DROP TABLE IF EXISTS mc_jdbc_test_thing");
        things = table().build(sql);
        things.createSchema();
    }

    private static DocumentTable.Builder<Thing> table() {
        return DocumentTable.of(Thing.class)
                .table("mc_jdbc_test_thing")
                .id("id", "UUID", Thing::id)
                .requiredColumn("namespace", "TEXT", Thing::namespace)
                .column("name", "TEXT", Thing::name)
                .column("kind", "TEXT", Thing::kind)
                .index("namespace", "name");
    }

    private static Thing thing(String ns, String name) {
        return new Thing(UUID.randomUUID(), ns, name, Kind.CHAT,
                Instant.parse("2026-09-03T10:15:30.123456Z"), List.of("a", "b"));
    }

    @Test
    void saveThenFindByIdReturnsAnEqualObject() {
        Thing t = thing("default", "one");
        things.save(t);
        assertThat(things.findById(t.id())).contains(t);
    }

    @Test
    void savingAgainReplacesColumnsAndDocument() {
        Thing t = thing("default", "one");
        things.save(t);
        Thing renamed = new Thing(t.id(), "other", "two", Kind.TOOL, t.createdAt(), List.of());
        things.save(renamed);

        assertThat(things.findById(t.id())).contains(renamed);
        assertThat(things.find("WHERE namespace = ?", "default")).isEmpty();
        assertThat(things.findOne("WHERE namespace = ? AND name = ?", "other", "two")).contains(renamed);
        assertThat(things.count("")).isEqualTo(1);
    }

    @Test
    void findTakesWhereOrderAndLimit() {
        things.save(thing("ns", "c"));
        things.save(thing("ns", "a"));
        things.save(thing("ns", "b"));
        things.save(thing("elsewhere", "z"));

        assertThat(things.find("WHERE namespace = ? ORDER BY name", "ns"))
                .extracting(Thing::name).containsExactly("a", "b", "c");
        assertThat(things.find("WHERE namespace = ? ORDER BY name LIMIT ? OFFSET ?", "ns", 2, 1))
                .extracting(Thing::name).containsExactly("b", "c");
        assertThat(things.findAll()).hasSize(4);
        assertThat(things.exists("WHERE kind = ?", Kind.CHAT)).isTrue();
        assertThat(things.exists("WHERE kind = ?", Kind.TOOL)).isFalse();
    }

    @Test
    void deleteByIdAndByClause() {
        Thing keep = thing("ns", "keep");
        Thing gone = thing("ns", "gone");
        things.save(keep);
        things.save(gone);
        things.save(thing("other", "x"));

        assertThat(things.deleteById(gone.id())).isTrue();
        assertThat(things.deleteById(gone.id())).isFalse();
        assertThat(things.delete("WHERE namespace = ?", "other")).isEqualTo(1);
        assertThat(things.findAll()).containsExactly(keep);
    }

    @Test
    void aColumnDeclaredLaterIsAddedToAnExistingTable() {
        things.save(thing("ns", "before"));

        DocumentTable<Thing> wider = table()
                .column("created_at", "TIMESTAMPTZ", Thing::createdAt)
                .build(sql);
        wider.createSchema();

        // The old row has no value yet; saving it again fills the column.
        assertThat(wider.find("WHERE created_at IS NULL")).hasSize(1);
        wider.save(thing("ns", "after"));
        assertThat(wider.find("WHERE created_at IS NOT NULL")).extracting(Thing::name).containsExactly("after");
    }

    record Header(UUID id, String name, Kind kind) { }

    @Test
    void selectReadsTheColumnsWithoutTheDocument() {
        Thing a = thing("ns", "a");
        Thing b = thing("ns", "b");
        things.save(b);
        things.save(a);

        List<Header> headers = things.select(
                row -> new Header(row.uuid("id"), row.string("name"), row.enumValue("kind", Kind.class)),
                "WHERE namespace = ? ORDER BY name", "ns");
        assertThat(headers).containsExactly(
                new Header(a.id(), "a", Kind.CHAT), new Header(b.id(), "b", Kind.CHAT));
    }

    @Test
    void theMapperServesHandWrittenStatements() {
        Thing t = thing("ns", "one");
        things.save(t);
        List<Thing> found = sql.query(
                "SELECT doc FROM mc_jdbc_test_thing WHERE doc->'tags' ?? ?", things.mapper(), "a");
        assertThat(found).containsExactly(t);
    }
}
