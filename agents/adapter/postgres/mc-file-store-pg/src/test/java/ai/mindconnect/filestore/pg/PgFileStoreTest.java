package ai.mindconnect.filestore.pg;

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

    private PgFileStore store;

    @BeforeEach
    void setUp() {
        Sql sql = Sql.of(requirePostgres());
        sql.execute("DROP TABLE IF EXISTS mc_file");
        store = new PgFileStore(sql).initSchema();
    }

    @Test
    void saveFindContentListDeleteRoundTrip() throws IOException {
        byte[] pdf = new byte[] {'%', 'P', 'D', 'F', 0, 1, 2, (byte) 0xFF};
        StoredFile first = store.save("report.pdf", "application/pdf", new ByteArrayInputStream(pdf));
        StoredFile second = store.save("notes.txt", "text/plain",
                new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));

        assertThat(first.id()).startsWith("file-").hasSize(25);
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
    void theBackendIsDiscoverableByType() {
        assertThat(FileStoreBackend.byType("postgres")).isPresent();
        var opened = FileStoreBackend.byType("postgres").orElseThrow().open(Map.of(
                "url", url(), "user", user(), "password", password()));
        assertThat(opened).isInstanceOf(PgFileStore.class);
        assertThat(opened.list()).isEmpty();
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
