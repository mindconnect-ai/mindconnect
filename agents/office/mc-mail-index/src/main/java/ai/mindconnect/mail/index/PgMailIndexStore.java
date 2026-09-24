package ai.mindconnect.mail.index;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.jdbc.DocumentTable;
import ai.mindconnect.jdbc.JdbcException;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.mail.Location;
import ai.mindconnect.mail.MailMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The windows in Postgres, a row per mail: {@code mc_mail_window} holds one
 * row per namespace, user, account and folder with what the window knows
 * beside its heads (total, when it was synced, the size it was filled to);
 * {@code mc_mail_head} one row per head in it, the head as the document, its
 * date beside it for the order, and an {@code embedding} column that a
 * semantic search is to fill — nothing writes it yet.
 *
 * <p>Keys are {@code (namespace, id)}; the id is user, account and folder —
 * and for a head its message id — each URL-encoded as the file adapter names
 * its path segments ({@code <user>/<account>/<folder>[/<message>]}).
 *
 * <p>{@link #save} writes the window row and brings the heads in line in one
 * transaction: a head still in the window has its document replaced — only
 * its document, so its embedding stays — and a head that left the window
 * leaves the table with its embedding. The window row is written first, so
 * two saves of one window take turns. {@link #load} reads both in one
 * statement, newest first as the window orders them.
 *
 * <p>The embedding column needs pgvector. Where the extension cannot be had,
 * the table is made without the column, and the first start that finds the
 * extension adds it.
 *
 * <p>The namespace is asked per call, like the file adapter's: one store
 * serves every namespace, and every statement matches the one of the moment.
 * A cache, like the files: nothing is imported from them, a window missing
 * here is filled again from the provider.
 */
public final class PgMailIndexStore implements MailIndexStore {

    private static final Logger log = LoggerFactory.getLogger(PgMailIndexStore.class);

    static final String WINDOWS = "mc_mail_window";
    static final String HEADS = "mc_mail_head";

    /** Written by hand, not by a {@link DocumentTable}: the heads of a window are written together, in bulk. */
    private static final String HEADS_DDL = """
            CREATE TABLE IF NOT EXISTS mc_mail_head (
                namespace   TEXT NOT NULL,
                id          TEXT NOT NULL,
                user_id     TEXT NOT NULL,
                account     TEXT NOT NULL,
                folder_id   TEXT NOT NULL,
                message_id  TEXT NOT NULL,
                received_at TIMESTAMPTZ,
                updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
                doc         JSONB NOT NULL,
                PRIMARY KEY (namespace, id)
            );
            CREATE INDEX IF NOT EXISTS mc_mail_head_window_idx
                ON mc_mail_head (namespace, user_id, account, folder_id, received_at DESC);
            """;

    private static final String EMBEDDING_DDL = "ALTER TABLE mc_mail_head ADD COLUMN IF NOT EXISTS embedding vector";

    private static final String THE_WINDOW = "namespace = ? AND user_id = ? AND account = ? AND folder_id = ?";

    /**
     * Every head of the window in one statement, from a JSON array. A head
     * whose document did not change is not written at all; the embedding is
     * never named, so it outlives every change of the document.
     */
    private static final String UPSERT_HEADS = """
            INSERT INTO mc_mail_head (namespace, id, user_id, account, folder_id, message_id, received_at, updated_at, doc)
            SELECT ?, e->>'key', ?, ?, ?, e->>'messageId', (e->>'receivedAt')::timestamptz, now(), e->'head'
            FROM jsonb_array_elements(?) AS e
            ON CONFLICT (namespace, id) DO UPDATE
                SET received_at = EXCLUDED.received_at, updated_at = now(), doc = EXCLUDED.doc
                WHERE mc_mail_head.doc IS DISTINCT FROM EXCLUDED.doc
            """;

    private static final String DELETE_GONE = "DELETE FROM mc_mail_head WHERE " + THE_WINDOW
            + " AND message_id NOT IN (SELECT jsonb_array_elements_text(?))";

    /** The window row and its heads in one snapshot; a window without heads is one row with a null head. */
    private static final String LOAD = """
            SELECT w.doc AS window_doc, h.doc AS head_doc
            FROM mc_mail_window w
            LEFT JOIN mc_mail_head h
                ON h.namespace = w.namespace AND h.user_id = w.user_id
                AND h.account = w.account AND h.folder_id = w.folder_id
            WHERE w.namespace = ? AND w.id = ?
            ORDER BY h.received_at DESC NULLS LAST, h.message_id
            """;

    private final Sql sql;
    private final Supplier<String> namespace;
    private final Predicate<Sql> vectorExtension;
    private final DocumentTable<WindowRow> windows;

    public PgMailIndexStore(Sql sql, Supplier<String> namespace) {
        this(sql, namespace, PgMailIndexStore::createVectorExtension);
    }

    /**
     * @param vectorExtension makes pgvector available in the database and says
     *                        whether it now is — the seam a test uses to be a
     *                        database without it
     */
    PgMailIndexStore(Sql sql, Supplier<String> namespace, Predicate<Sql> vectorExtension) {
        this.sql = Objects.requireNonNull(sql, "sql");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.vectorExtension = Objects.requireNonNull(vectorExtension, "vectorExtension");
        this.windows = windows(sql);
    }

    /**
     * Runs the idempotent DDL of both tables, and adds the embedding column
     * when pgvector is there — or says once that it is not.
     */
    public PgMailIndexStore initSchema() {
        windows.createSchema();
        sql.execute(HEADS_DDL);
        if (vectorExtension.test(sql)) {
            sql.execute(EMBEDDING_DDL);
        } else {
            log.warn("pgvector is not available in this database: {} is kept without its embedding column,"
                    + " which is added on the first start that finds the extension", HEADS);
        }
        return this;
    }

    private static boolean createVectorExtension(Sql sql) {
        try {
            sql.execute("CREATE EXTENSION IF NOT EXISTS vector");
            return true;
        } catch (JdbcException e) {
            log.debug("CREATE EXTENSION vector failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public Optional<FolderWindow> load(UserId user, Location location) {
        List<String[]> rows = sql.query(LOAD, row -> new String[]{row.string("window_doc"), row.string("head_doc")},
                namespace.get(), key(user.value(), location));
        if (rows.isEmpty()) return Optional.empty();
        try {
            WindowRow window = sql.json().read(rows.get(0)[0], WindowRow.class);
            List<MailMessage> heads = new ArrayList<>(rows.size());
            for (String[] row : rows) {
                if (row[1] != null) heads.add(sql.json().read(row[1], MailMessage.class));
            }
            return Optional.of(new FolderWindow(location, heads, window.total(), window.syncedAt(), window.filledTo()));
        } catch (JdbcException e) {
            // A window that cannot be read is one that is filled again.
            return Optional.empty();
        }
    }

    @Override
    public void save(UserId user, FolderWindow window) {
        String ns = namespace.get();
        Location at = window.location();
        Map<String, Map<String, Object>> heads = new LinkedHashMap<>();
        for (MailMessage head : window.heads()) {
            Map<String, Object> element = new LinkedHashMap<>();
            element.put("key", key(user.value(), at) + "/" + FileMailIndexStore.segment(head.id()));
            element.put("messageId", head.id());
            element.put("receivedAt", head.receivedAt() == null ? null : head.receivedAt().toString());
            element.put("head", head);
            heads.putIfAbsent(head.id(), element);
        }
        sql.inTransaction(tx -> {
            windows(tx).save(WindowRow.of(user, window));
            if (!heads.isEmpty()) {
                tx.update(UPSERT_HEADS, ns, user.value(), at.account(), at.folderId(),
                        tx.json().jsonb(List.copyOf(heads.values())));
            }
            tx.update(DELETE_GONE, ns, user.value(), at.account(), at.folderId(),
                    tx.json().jsonb(List.copyOf(heads.keySet())));
        });
    }

    @Override
    public void delete(UserId user, Location location) {
        String ns = namespace.get();
        sql.inTransaction(tx -> {
            windows(tx).deleteById(ns, key(user.value(), location));
            tx.update("DELETE FROM " + HEADS + " WHERE " + THE_WINDOW,
                    ns, user.value(), location.account(), location.folderId());
        });
    }

    @Override
    public List<Location> windows(UserId user) {
        return windows.select(row -> new Location(row.string("account"), row.string("folder_id")),
                "WHERE namespace = ? AND user_id = ? ORDER BY account, folder_id", namespace.get(), user.value());
    }

    private DocumentTable<WindowRow> windows(Sql on) {
        return DocumentTable.of(WindowRow.class)
                .table(WINDOWS)
                .partitionKey("namespace", "TEXT", r -> namespace.get())
                .id("id", "TEXT", r -> key(r.user(), r.location()))
                .requiredColumn("user_id", "TEXT", WindowRow::user)
                .requiredColumn("account", "TEXT", r -> r.location().account())
                .requiredColumn("folder_id", "TEXT", r -> r.location().folderId())
                .index("namespace", "user_id")
                .build(on);
    }

    private static String key(String user, Location location) {
        return FileMailIndexStore.segment(user) + "/" + FileMailIndexStore.segment(location.account())
                + "/" + FileMailIndexStore.segment(location.folderId());
    }

    /** The document of a window row: whose window, and everything it knows besides its heads. */
    record WindowRow(String user, Location location, long total, Instant syncedAt, int filledTo) {

        static WindowRow of(UserId user, FolderWindow window) {
            return new WindowRow(user.value(), window.location(), window.total(), window.syncedAt(),
                    window.filledTo());
        }
    }
}
