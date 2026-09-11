package ai.mindconnect.filestore.adapter.pg;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.filestore.FileId;
import ai.mindconnect.filestore.FileStoreBackend;
import ai.mindconnect.filestore.StoredFile;
import ai.mindconnect.jdbc.Sql;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Against a real Postgres; skipped when none answers on 5433. */
class PgFileStoreTest {

    private static final Namespace NS = new Namespace("test");

    private Sql sql;
    private PgFileStore store;

    @BeforeEach
    void setUp() {
        sql = Sql.of(requirePostgres());
        sql.execute("DROP TABLE IF EXISTS mc_file");
        store = new PgFileStore(sql, NS).initSchema();
    }

    @Test
    void theCreatorIsStoredAndReadBack() throws IOException {
        StoredFile alices = store.save("a.txt", "text/plain", new ByteArrayInputStream(new byte[] {1}),
                UserId.of("alice"));
        StoredFile nobodys = store.save("b.txt", "text/plain", new ByteArrayInputStream(new byte[] {2}));

        assertThat(alices.creator()).isEqualTo(UserId.of("alice"));
        assertThat(store.find(alices.id())).contains(alices);
        assertThat(store.find(nobodys.id())).get().extracting(StoredFile::creator).isNull();
        assertThat(store.list()).containsExactlyInAnyOrder(alices, nobodys);
    }

    @Test
    void aTableFromBeforeCreatorsGainsTheColumnAndItsRowsHaveNone() throws IOException {
        sql.execute("DROP TABLE IF EXISTS mc_file");
        sql.execute("""
                CREATE TABLE mc_file (
                    namespace    TEXT NOT NULL,
                    id           TEXT NOT NULL,
                    name         TEXT NOT NULL,
                    content_type TEXT,
                    size         BIGINT NOT NULL,
                    created_at   TIMESTAMPTZ NOT NULL,
                    content      BYTEA NOT NULL,
                    PRIMARY KEY (namespace, id)
                );
                INSERT INTO mc_file VALUES ('test', 'file-0123456789abcdef0123', 'old.txt', 'text/plain', 1,
                                            now(), decode('01', 'hex'));
                """);

        PgFileStore upgraded = new PgFileStore(sql, NS).initSchema();

        assertThat(upgraded.find(FileId.of("file-0123456789abcdef0123"))).get()
                .extracting(StoredFile::name, StoredFile::creator).containsExactly("old.txt", null);
        StoredFile fresh = upgraded.save("new.txt", "text/plain", new ByteArrayInputStream(new byte[] {2}),
                UserId.of("bob"));
        assertThat(upgraded.find(fresh.id())).get().extracting(StoredFile::creator).isEqualTo(UserId.of("bob"));
    }

    @Test
    void saveFindContentListDeleteRoundTrip() throws IOException {
        byte[] pdf = new byte[] {'%', 'P', 'D', 'F', 0, 1, 2, (byte) 0xFF};
        StoredFile first = store.save("report.pdf", "application/pdf", new ByteArrayInputStream(pdf));
        StoredFile second = store.save("notes.txt", "text/plain",
                new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));

        assertThat(first.id().value()).startsWith("file-").hasSize(25);
        assertThat(first.size()).isEqualTo(pdf.length);
        assertThat(store.find(first.id())).contains(first);
        assertThat(store.content(first.id()).readAllBytes()).isEqualTo(pdf);
        assertThat(store.list()).containsExactly(second, first);   // newest first

        store.delete(first.id());
        assertThat(store.find(first.id())).isEmpty();
        assertThat(store.list()).containsExactly(second);
        assertThatThrownBy(() -> store.content(first.id())).isInstanceOf(IOException.class);
        store.delete(first.id());   // gone already: not an error
    }

    @Test
    void namesAreSanitisedLikeTheFilesystemAdapterDoes() throws IOException {
        StoredFile f = store.save("../../etc/passwd", null, new ByteArrayInputStream(new byte[0]));
        assertThat(f.name()).isEqualTo("passwd");
        StoredFile blank = store.save("  ", "application/octet-stream", new ByteArrayInputStream(new byte[] {1}));
        assertThat(blank.name()).isEqualTo("upload.bin");
        assertThat(store.find(f.id())).get().extracting(StoredFile::contentType).isNull();
    }

    @Test
    void aStoreBoundToAnotherNamespaceSeesNothing() throws IOException {
        PgFileStore other = new PgFileStore(sql, new Namespace("other")).initSchema();
        StoredFile file = store.save("report.pdf", "application/pdf", new ByteArrayInputStream(new byte[] {1, 2}));

        assertThat(other.find(file.id())).isEmpty();
        assertThat(other.list()).isEmpty();
        assertThatThrownBy(() -> other.content(file.id())).isInstanceOf(IOException.class);
        other.delete(file.id());

        assertThat(store.find(file.id())).contains(file);
        assertThat(store.content(file.id()).readAllBytes()).containsExactly(1, 2);
        assertThat(store.list()).containsExactly(file);
    }

    @Test
    void theBackendIsDiscoverableByTypeAndBoundToTheConfiguredNamespace() throws IOException {
        assertThat(FileStoreBackend.byType("postgres")).isPresent();
        StoredFile mine = store.save("notes.txt", "text/plain", new ByteArrayInputStream(new byte[] {1}));

        var opened = FileStoreBackend.byType("postgres").orElseThrow().open(Map.of(
                "url", url(), "user", user(), "password", password(), "namespace", "another"));
        assertThat(opened).isInstanceOf(PgFileStore.class);
        assertThat(opened.list()).isEmpty();

        var sameNamespace = FileStoreBackend.byType("postgres").orElseThrow().open(Map.of(
                "url", url(), "user", user(), "password", password(), "namespace", NS.value()));
        assertThat(sameNamespace.list()).containsExactly(mine);
    }

    @Test
    void theBackendRefusesAMissingOrBlankNamespace() {
        var backend = FileStoreBackend.byType("postgres").orElseThrow();
        assertThatThrownBy(() -> backend.open(Map.of("url", url(), "user", user(), "password", password())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("namespace");
        assertThatThrownBy(() -> backend.open(Map.of("url", url(), "namespace", " ")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("namespace");
    }

    private static String url() { return System.getenv().getOrDefault("MC_JDBC_TEST_URL", "jdbc:postgresql://localhost:5433/postgres"); }
    private static String user() { return System.getenv().getOrDefault("MC_JDBC_TEST_USER", "postgres"); }
    private static String password() { return System.getenv().getOrDefault("MC_JDBC_TEST_PASSWORD", "test"); }

    private static DataSource requirePostgres() {
        var ds = new PGSimpleDataSource();
        ds.setUrl(url()); ds.setUser(user()); ds.setPassword(password());
        try (Connection c = ds.getConnection()) {
            assumeTrue(c.isValid(2));
        } catch (Exception e) {
            assumeTrue(false, "no Postgres reachable — skipping");
        }
        return ds;
    }
}
