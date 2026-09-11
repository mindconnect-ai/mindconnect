package ai.mindconnect.filestore.adapter.pg;

import ai.mindconnect.agent.EntityId;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.filestore.FileId;
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
import java.util.Objects;
import java.util.Optional;

/**
 * {@link FileStore} on Postgres: one row of {@code mc_file} per upload, keyed
 * by {@code (namespace, id)} — the {@link StoredFile} metadata as columns, the
 * content as {@code bytea}. Ids are generated exactly as the filesystem
 * adapter generates them, so a file id looks the same whichever backend
 * produced it. The store is bound to one namespace and every statement
 * matches it.
 *
 * <p>Content goes in and out whole. Uploads are documents, not videos; a
 * store that has to stream gigabytes wants an object store, not a table.
 */
public final class PgFileStore implements FileStore {

    /**
     * The {@code creator} column came later; the {@code ALTER} adds it to a
     * table created before, whose rows then have no creator.
     */
    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS mc_file (
                namespace    TEXT NOT NULL,
                id           TEXT NOT NULL,
                name         TEXT NOT NULL,
                content_type TEXT,
                size         BIGINT NOT NULL,
                created_at   TIMESTAMPTZ NOT NULL,
                content      BYTEA NOT NULL,
                PRIMARY KEY (namespace, id)
            );
            ALTER TABLE mc_file ADD COLUMN IF NOT EXISTS creator TEXT;
            """;

    private static final String SELECT = "SELECT id, name, content_type, size, created_at, creator FROM mc_file";

    private final Sql sql;
    private final Namespace namespace;

    public PgFileStore(DataSource dataSource, Namespace namespace) {
        this(Sql.of(dataSource), namespace);
    }

    public PgFileStore(Sql sql, Namespace namespace) {
        this.sql = sql;
        this.namespace = Objects.requireNonNull(namespace, "namespace");
    }

    /** Runs the idempotent DDL ({@code CREATE TABLE IF NOT EXISTS …}, {@code ADD COLUMN IF NOT EXISTS …}). */
    public PgFileStore initSchema() {
        sql.execute(DDL);
        return this;
    }

    @Override
    public StoredFile save(String name, String contentType, InputStream content, UserId creator) throws IOException {
        FileId id = FileId.of("file-" + EntityId.randomValue().replace("-", "").substring(0, 20));
        String safeName = Path.of(name == null || name.isBlank() ? "upload.bin" : name)
                .getFileName().toString().replaceAll("[^A-Za-z0-9._ -]", "_");
        byte[] bytes = content.readAllBytes();
        StoredFile file = new StoredFile(id, safeName, contentType, bytes.length, Instant.now(), creator);
        sql.update("INSERT INTO mc_file (namespace, id, name, content_type, size, created_at, content, creator) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                namespace.value(), id.value(), file.name(), file.contentType(), file.size(), file.createdAt(), bytes,
                creator == null ? null : creator.value());
        return file;
    }

    @Override
    public Optional<StoredFile> find(FileId id) {
        return sql.queryOne(SELECT + " WHERE namespace = ? AND id = ?",
                PgFileStore::storedFile, namespace.value(), id.value());
    }

    @Override
    public InputStream content(FileId id) throws IOException {
        return sql.queryOne("SELECT content FROM mc_file WHERE namespace = ? AND id = ?",
                        row -> row.bytes("content"), namespace.value(), id.value())
                .map(bytes -> (InputStream) new ByteArrayInputStream(bytes))
                .orElseThrow(() -> new IOException("No stored file with id '" + id + "'"));
    }

    /** Newest first, as the filesystem adapter lists. */
    @Override
    public List<StoredFile> list() {
        return sql.query(SELECT + " WHERE namespace = ? ORDER BY created_at DESC, id",
                PgFileStore::storedFile, namespace.value());
    }

    @Override
    public void delete(FileId id) {
        sql.update("DELETE FROM mc_file WHERE namespace = ? AND id = ?", namespace.value(), id.value());
    }

    private static StoredFile storedFile(Row row) throws SQLException {
        Long size = row.longValue("size");
        String creator = row.string("creator");
        return new StoredFile(FileId.of(row.string("id")), row.string("name"),
                row.string("content_type"), size == null ? 0L : size, row.instant("created_at"),
                creator == null ? null : UserId.of(creator));
    }
}
