package ai.mindconnect.namespace.adapter.pg;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.NamespacePurge;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.namespace.service.NamespaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Removes a namespace's rows from Postgres. Every namespaced table of the
 * agents area carries the namespace in a column named {@code namespace}
 * (the workflow area's tables call it {@code partition_key}), and the
 * pgvector backend keeps one table per store, named
 * {@code vs_<namespace>__<store>} — so the purge asks the catalogue for
 * both, rather than keeping a list that every new store would have to
 * extend.
 */
public class PgNamespacePurge implements NamespacePurge {

    private static final Logger log = LoggerFactory.getLogger(PgNamespacePurge.class);

    private final Sql sql;

    public PgNamespacePurge(DataSource dataSource) {
        this(Sql.of(dataSource));
    }

    public PgNamespacePurge(Sql sql) {
        this.sql = Objects.requireNonNull(sql, "sql");
    }

    @Override
    public void purge(Namespace namespace) {
        String id = namespace.value();
        if (!NamespaceService.ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Refusing to purge '" + id + "'");
        }
        int rows = 0;
        for (String column : List.of("namespace", "partition_key")) {
            List<String> tables = sql.query(
                    "SELECT table_name FROM information_schema.columns "
                            + "WHERE table_schema = current_schema() AND column_name = ? ORDER BY table_name",
                    row -> row.string("table_name"), column);
            for (String table : tables) {
                rows += sql.update("DELETE FROM " + quote(table) + " WHERE " + column + " = ?", id);
            }
        }
        String prefix = "vs_" + id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_") + "__";
        List<String> stores = sql.query(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = current_schema() AND table_name LIKE ? ORDER BY table_name",
                row -> row.string("table_name"), escapeLike(prefix) + "%");
        for (String table : stores) {
            sql.execute("DROP TABLE IF EXISTS " + quote(table));
        }
        log.info("Purged namespace '{}' from Postgres: {} rows, {} vector-store tables", id, rows, stores.size());
    }

    private static String quote(String table) {
        return "\"" + table.replace("\"", "\"\"") + "\"";
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("_", "\\_").replace("%", "\\%");
    }
}
