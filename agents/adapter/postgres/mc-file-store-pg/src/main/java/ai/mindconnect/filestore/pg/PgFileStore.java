package ai.mindconnect.filestore.pg;

import ai.mindconnect.filestore.FileStore;
import ai.mindconnect.filestore.StoredFile;
import ai.mindconnect.jdbc.Row;
import ai.mindconnect.jdbc.Sql;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link FileStore} on Postgres: one row of {@code mc_file} per upload —
 * the {@link StoredFile} metadata as columns, the content as {@code bytea}.
 * Ids are generated exactly as the filesystem adapter generates them, so a
 * file id looks the same whichever backend produced it.
 *
 * <p>Content goes in and out whole. Uploads are documents, not videos; a
 * store that has to stream gigabytes wants an object store, not a table.
 */
public final class PgFileStore implements FileStore {

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS mc_file (
                id           TEXT PRIMARY KEY,
                name         TEXT NOT NULL,
                content_type TEXT,
                size         BIGINT NOT NULL,
                created_at   TIMESTAMPTZ NOT NULL,
                content      BYTEA NOT NULL
            );
            """;

    private final Sql sql;

    public PgFileStore(DataSource dataSource) {
        this(Sql.of(dataSource));
    }

    public PgFileStore(Sql sql) {
        this.sql = sql;
    }

    /** Runs the idempotent DDL ({@code CREATE TABLE IF NOT EXISTS …}). */
    public PgFileStore initSchema() {
        sql.execute(DDL);
        return this;
    }

    @Override
    public StoredFile save(String name, String contentType, InputStream content) throws IOException {
        String id = "file-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String safeName = Path.of(name == null || name.isBlank() ? "upload.bin" : name)
                .getFileName().toString().replaceAll("[^A-Za-z0-9._ -]", "_");
        byte[] bytes = content.readAllBytes();
        StoredFile file = new StoredFile(id, safeName, contentType, bytes.length, Instant.now());
        sql.update("INSERT INTO mc_file (id, name, content_type, size, created_at, content) VALUES (?, ?, ?, ?, ?, ?)",
                file.id(), file.name(), file.contentType(), file.size(), file.createdAt(), bytes);
        return file;
    }

    @Override
    public Optional<StoredFile> find(String id) {
        return sql.queryOne("SELECT id, name, content_type, size, created_at FROM mc_file WHERE id = ?",
                PgFileStore::storedFile, id);
    }

    @Override
    public InputStream content(String id) throws IOException {
        return sql.queryOne("SELECT content FROM mc_file WHERE id = ?", row -> row.bytes("content"), id)
                .map(bytes -> (InputStream) new ByteArrayInputStream(bytes))
                .orElseThrow(() -> new IOException("No stored file with id '" + id + "'"));
    }

    /** Newest first, as the filesystem adapter lists. */
    @Override
    public List<StoredFile> list() {
        return sql.query("SELECT id, name, content_type, size, created_at FROM mc_file ORDER BY created_at DESC, id",
                PgFileStore::storedFile);
    }

    @Override
    public void delete(String id) {
        sql.update("DELETE FROM mc_file WHERE id = ?", id);
    }

    private static StoredFile storedFile(Row row) throws SQLException {
        Long size = row.longValue("size");
        return new StoredFile(row.string("id"), row.string("name"), row.string("content_type"),
                size == null ? 0L : size, row.instant("created_at"));
    }
}
