package ai.mindconnect.mail.view;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.jdbc.DocumentTable;
import ai.mindconnect.jdbc.Sql;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The views in Postgres: one row of {@code mc_mail_view} per namespace, user
 * and view, keyed by {@code (namespace, id)} where the id is the user and the
 * view together ({@code <user>/<view id>}, the user URL-encoded so the first
 * slash is the seam). Beside the document stand the user, the view id and
 * the kind, so a user's views are one indexed query.
 *
 * <p>A row per view, not a document per user as the file keeps: two tools
 * saving two views of one person at once write two rows and cannot lose
 * each other's. Every call is one statement on one row.
 *
 * <p>The namespace is asked per call, like the file store's: a server binds
 * it per request, and a store built at start-up has no request to ask — so
 * one store serves every namespace, and every statement matches the one of
 * the moment.
 *
 * <p>With an import source (the {@link FileViewStore} a host used before),
 * the first call for a user who has no row yet copies that user's views over,
 * once: {@code mc_mail_view_import} remembers who was looked at, so deleting
 * every view does not bring the file's back. The file is left where it is.
 */
public final class PgViewStore implements ViewStore {

    static final String TABLE = "mc_mail_view";
    static final String IMPORTS = "mc_mail_view_import";

    private final Sql sql;
    private final Supplier<Namespace> namespace;
    private final ViewStore importFrom;
    private final DocumentTable<ViewRow> views;
    /** Namespace and user already imported, or found to need nothing — asked once per process. */
    private final Set<String> imported = ConcurrentHashMap.newKeySet();

    public PgViewStore(Sql sql, Supplier<Namespace> namespace) {
        this(sql, namespace, null);
    }

    /**
     * @param importFrom where a user's views were kept before, read once for a
     *                   user this table has never seen; null for none. It must
     *                   resolve the namespace the same way this store does.
     */
    public PgViewStore(Sql sql, Supplier<Namespace> namespace, ViewStore importFrom) {
        this.sql = Objects.requireNonNull(sql, "sql");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.importFrom = importFrom;
        this.views = views(sql);
    }

    /** Runs the idempotent DDL ({@code CREATE TABLE IF NOT EXISTS …}) of both tables. */
    public PgViewStore initSchema() {
        views.createSchema();
        imports(sql).createSchema();
        return this;
    }

    @Override
    public Optional<StoredView> load(UserId user, ViewId id) {
        String ns = namespace();
        importOnce(ns, user);
        return views.findById(ns, key(user, id)).map(ViewRow::toView);
    }

    @Override
    public void save(StoredView view) {
        importOnce(namespace(), view.owner());
        views.save(ViewRow.of(view));
    }

    @Override
    public void delete(UserId user, ViewId id) {
        String ns = namespace();
        importOnce(ns, user);
        views.deleteById(ns, key(user, id));
    }

    @Override
    public List<StoredView> list(UserId user, String kind) {
        String ns = namespace();
        importOnce(ns, user);
        List<ViewRow> rows = kind == null
                ? views.find("WHERE namespace = ? AND user_id = ?", ns, user.value())
                : views.find("WHERE namespace = ? AND user_id = ? AND kind = ?", ns, user.value(), kind);
        List<StoredView> out = new ArrayList<>(rows.size());
        for (ViewRow row : rows) out.add(row.toView());
        // Sorted here, as the file store sorts: the view's own time, not the row's.
        out.sort(Comparator.comparing(StoredView::updatedAt).reversed());
        return out;
    }

    // ── the one-time import ─────────────────────────────────────────────────

    /**
     * Copies the user's views from the import source when this is the first
     * time the table sees them. The marker row is inserted first, in the same
     * transaction: a second caller for the same user waits on it and then finds
     * it there, so the views are copied once. A user who already has rows —
     * saved here before an import source was configured — keeps them, and
     * nothing is copied.
     */
    private void importOnce(String ns, UserId user) {
        if (importFrom == null) return;
        String who = ns + "\n" + user.value();
        if (imported.contains(who)) return;
        sql.inTransaction(tx -> {
            if (!imports(tx).insert(new ImportMark(user.value(), Instant.now()))) return;
            DocumentTable<ViewRow> rows = views(tx);
            if (rows.exists("WHERE namespace = ? AND user_id = ?", ns, user.value())) return;
            for (StoredView view : importFrom.list(user, null)) {
                // Insert, never overwrite: what is in the table is newer than the file.
                rows.insert(ViewRow.of(view));
            }
        });
        imported.add(who);
    }

    // ── tables ──────────────────────────────────────────────────────────────

    private String namespace() {
        return namespace.get().value();
    }

    private DocumentTable<ViewRow> views(Sql on) {
        return DocumentTable.of(ViewRow.class)
                .table(TABLE)
                .partitionKey("namespace", "TEXT", r -> namespace())
                .id("id", "TEXT", r -> key(r.user(), r.view()))
                .requiredColumn("user_id", "TEXT", ViewRow::user)
                .requiredColumn("view_id", "TEXT", ViewRow::view)
                .requiredColumn("kind", "TEXT", r -> r.stored().kind)
                .index("namespace", "user_id")
                .build(on);
    }

    private DocumentTable<ImportMark> imports(Sql on) {
        return DocumentTable.of(ImportMark.class)
                .table(IMPORTS)
                .partitionKey("namespace", "TEXT", m -> namespace())
                .id("user_id", "TEXT", ImportMark::user)
                .build(on);
    }

    private static String key(UserId user, ViewId id) {
        return key(user.value(), id.value());
    }

    /** The user URL-encoded — it has no slash then — and the view id after the first slash. */
    private static String key(String user, String view) {
        return URLEncoder.encode(user, StandardCharsets.UTF_8) + "/" + view;
    }

    /**
     * The document of a row: whose, which, and the view in the shape the file
     * keeps it in ({@link FileViewStore.Record}) — plain fields, readable
     * without the domain types.
     */
    record ViewRow(String user, String view, FileViewStore.Record stored) {

        static ViewRow of(StoredView view) {
            return new ViewRow(view.owner().value(), view.id().value(), FileViewStore.Record.of(view));
        }

        StoredView toView() {
            return stored.toView(UserId.of(user), ViewId.of(view));
        }
    }

    /** That a user's views were looked for in the import source, and when. */
    record ImportMark(String user, Instant importedAt) {
    }
}
