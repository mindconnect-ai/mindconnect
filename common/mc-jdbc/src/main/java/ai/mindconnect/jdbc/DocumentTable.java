package ai.mindconnect.jdbc;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * One table, one document per row. The whole object is stored as JSON in a
 * {@code doc} column; next to it stand only the columns a query needs — the
 * id, and whatever you declare with {@link Builder#column}. The document is
 * the truth, the columns are the index: writing renders both from the
 * object, reading deserializes {@code doc} alone.
 *
 * <pre>{@code
 * DocumentTable<LlmConfig> configs = DocumentTable.of(LlmConfig.class)
 *         .table("mc_llm_config")
 *         .id("id", "UUID", LlmConfig::getId)
 *         .requiredColumn("name", "TEXT", LlmConfig::getName)
 *         .uniqueIndex("name")
 *         .build(sql);
 *
 * configs.createSchema();                 // CREATE TABLE IF NOT EXISTS …
 * configs.save(config);                   // upsert on id
 * configs.findById(id);
 * configs.find("WHERE name = ?", name);   // the tail after FROM <table>
 * configs.find("ORDER BY name");
 * configs.deleteById(id);
 * }</pre>
 *
 * <p>Queries take the SQL tail after {@code FROM <table>} — a WHERE, an ORDER
 * BY, a LIMIT, or nothing — so the caller writes exactly the SQL they mean
 * and this class writes none of it for them. For anything the tail cannot
 * express, {@link #mapper()} maps a {@code doc} column from any statement.
 *
 * <p>Columns therefore serve two purposes: what a query filters or sorts by,
 * and what a list shows. {@link #select} reads only the columns, so a long
 * list of heavy documents costs no deserialization at all.
 *
 * <p>The schema is idempotent and additive: {@link #createSchema} creates
 * the table if missing, adds a declared column that an older table lacks,
 * and creates the declared indexes. A column added later to a table with
 * rows is nullable and empty until the rows are saved again.
 */
public final class DocumentTable<T> {

    private record Column<T>(String name, String type, boolean required, Function<T, Object> value) { }

    private record Index(List<String> columns, boolean unique) { }

    private final Sql sql;
    private final Class<T> type;
    private final String table;
    private final Column<T> partition;
    private final Column<T> id;
    private final List<Column<T>> columns;
    private final List<Index> indexes;
    private final RowMapper<T> mapper;
    private final String upsert;

    private DocumentTable(Builder<T> b) {
        this.sql = b.sql;
        this.type = b.type;
        this.table = b.table;
        this.partition = b.partition;
        this.id = b.id;
        this.columns = List.copyOf(b.columns);
        this.indexes = List.copyOf(b.indexes);
        this.mapper = row -> row.json("doc", type);
        this.upsert = buildUpsert();
    }

    public static <T> Builder<T> of(Class<T> type) {
        return new Builder<>(type);
    }

    // ── schema ──────────────────────────────────────────────────────────────

    /** The DDL {@link #createSchema()} runs, for reading or for a migration script. */
    public String ddl() {
        StringBuilder out = new StringBuilder();
        out.append("CREATE TABLE IF NOT EXISTS ").append(table).append(" (\n");
        if (partition == null) {
            out.append("    ").append(id.name()).append(' ').append(id.type()).append(" PRIMARY KEY,\n");
        } else {
            out.append("    ").append(partition.name()).append(' ').append(partition.type()).append(" NOT NULL,\n");
            out.append("    ").append(id.name()).append(' ').append(id.type()).append(" NOT NULL,\n");
        }
        for (Column<T> c : columns) {
            out.append("    ").append(c.name()).append(' ').append(c.type());
            if (c.required()) out.append(" NOT NULL");
            out.append(",\n");
        }
        out.append("    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),\n");
        out.append("    doc JSONB NOT NULL");
        if (partition != null) {
            out.append(",\n    PRIMARY KEY (").append(partition.name()).append(", ").append(id.name()).append(')');
        }
        out.append("\n);\n");
        for (Column<T> c : columns) {
            out.append("ALTER TABLE ").append(table).append(" ADD COLUMN IF NOT EXISTS ")
               .append(c.name()).append(' ').append(c.type()).append(";\n");
        }
        for (Index i : indexes) {
            out.append("CREATE ").append(i.unique() ? "UNIQUE " : "").append("INDEX IF NOT EXISTS ")
               .append(table).append('_').append(String.join("_", i.columns())).append("_idx ON ")
               .append(table).append(" (").append(String.join(", ", i.columns())).append(");\n");
        }
        return out.toString();
    }

    /** Creates the table if missing, adds a declared column an older table lacks, and creates the indexes. */
    public void createSchema() {
        sql.execute(ddl());
    }

    // ── reading ─────────────────────────────────────────────────────────────

    public Optional<T> findById(Object idValue) {
        requireNoPartition("findById");
        return sql.queryOne(select("WHERE " + id.name() + " = ?"), mapper, idValue);
    }

    /** For a table with a {@link Builder#partitionKey partition key}: the row with this key pair. */
    public Optional<T> findById(Object partitionValue, Object idValue) {
        requirePartition("findById");
        return sql.queryOne(select(whereKey()), mapper, partitionValue, idValue);
    }

    /** {@code tail} is everything after {@code FROM <table>}: WHERE, ORDER BY, LIMIT — or empty. */
    public List<T> find(String tail, Object... params) {
        return sql.query(select(tail), mapper, params);
    }

    public Optional<T> findOne(String tail, Object... params) {
        return sql.queryOne(select(tail), mapper, params);
    }

    public List<T> findAll() {
        return find("");
    }

    public int count(String tail, Object... params) {
        Long n = sql.scalar("SELECT count(*) FROM " + table + " " + tail, Long.class, params);
        return n == null ? 0 : n.intValue();
    }

    public boolean exists(String tail, Object... params) {
        return count(tail, params) > 0;
    }

    /**
     * Maps a {@code doc} column to {@code T}, for a statement written by hand against {@link Sql}.
     * On a table with a partition key the statement selects the partition column too, which
     * fills in a document that lacks the field.
     */
    public RowMapper<T> mapper() {
        return mapper;
    }

    /**
     * A projection: the id, the declared columns and {@code updated_at} —
     * but not the document — mapped into something lighter than {@code T}.
     * This is how a long list is read without deserializing every row's
     * document: declare the columns the list shows, then select them.
     */
    public <S> List<S> select(RowMapper<S> mapper, String tail, Object... params) {
        return sql.query("SELECT " + columnList() + " FROM " + table + " " + tail, mapper, params);
    }

    /** The columns {@link #select} reads: id, the declared ones, {@code updated_at}. */
    public String columnList() {
        List<String> names = keyNames();
        columns.forEach(c -> names.add(c.name()));
        names.add("updated_at");
        return String.join(", ", names);
    }

    // ── writing ─────────────────────────────────────────────────────────────

    /** Insert or, if a row with this id exists, replace its columns and document. */
    public T save(T entity) {
        int keys = partition == null ? 1 : 2;
        Object[] params = new Object[columns.size() + keys + 1];
        if (partition != null) params[0] = partition.value().apply(entity);
        params[keys - 1] = id.value().apply(entity);
        for (int i = 0; i < columns.size(); i++) {
            params[i + keys] = columns.get(i).value().apply(entity);
        }
        params[params.length - 1] = sql.json().jsonb(entity);
        sql.update(upsert, params);
        return entity;
    }

    public boolean deleteById(Object idValue) {
        requireNoPartition("deleteById");
        return sql.update("DELETE FROM " + table + " WHERE " + id.name() + " = ?", idValue) > 0;
    }

    /** For a table with a {@link Builder#partitionKey partition key}: deletes the row with this key pair. */
    public boolean deleteById(Object partitionValue, Object idValue) {
        requirePartition("deleteById");
        return sql.update("DELETE FROM " + table + " " + whereKey(), partitionValue, idValue) > 0;
    }

    /** {@code tail} must start with WHERE — a delete without one is a bug, so it is refused. */
    public int delete(String tail, Object... params) {
        if (!tail.stripLeading().regionMatches(true, 0, "WHERE", 0, 5)) {
            throw new JdbcException("delete() needs a WHERE clause; use deleteAll() to empty the table");
        }
        return sql.update("DELETE FROM " + table + " " + tail, params);
    }

    public int deleteAll() {
        return sql.update("DELETE FROM " + table);
    }

    // ── SQL ─────────────────────────────────────────────────────────────────

    public String table() {
        return table;
    }

    private String select(String tail) {
        String read = partition == null ? "doc" : partition.name() + ", doc";
        return "SELECT " + read + " FROM " + table + " " + tail;
    }

    private List<String> keyNames() {
        List<String> names = new ArrayList<>();
        if (partition != null) names.add(partition.name());
        names.add(id.name());
        return names;
    }

    private String whereKey() {
        return "WHERE " + partition.name() + " = ? AND " + id.name() + " = ?";
    }

    private void requirePartition(String method) {
        if (partition == null) {
            throw new IllegalStateException(table + " has a single-column key; call " + method + "(id)");
        }
    }

    private void requireNoPartition(String method) {
        if (partition != null) {
            throw new IllegalStateException(table + " is keyed by (" + partition.name() + ", " + id.name()
                    + "); call " + method + "(" + partition.name() + ", id)");
        }
    }

    private String buildUpsert() {
        List<String> names = keyNames();
        columns.forEach(c -> names.add(c.name()));
        String placeholders = names.stream().map(n -> "?").collect(Collectors.joining(", "));
        String updates = columns.stream()
                .map(c -> c.name() + " = EXCLUDED." + c.name())
                .collect(Collectors.joining(", "));
        return "INSERT INTO " + table + " (" + String.join(", ", names) + ", updated_at, doc) VALUES ("
                + placeholders + ", now(), ?) ON CONFLICT (" + String.join(", ", keyNames()) + ") DO UPDATE SET "
                + (updates.isEmpty() ? "" : updates + ", ")
                + "updated_at = now(), doc = EXCLUDED.doc";
    }

    /** The statement {@link #save} runs — visible so a test or a reader can check it. */
    public String upsertSql() {
        return upsert;
    }

    // ── builder ─────────────────────────────────────────────────────────────

    public static final class Builder<T> {

        private final Class<T> type;
        private Sql sql;
        private String table;
        private Column<T> partition;
        private Column<T> id;
        private final List<Column<T>> columns = new ArrayList<>();
        private final List<Index> indexes = new ArrayList<>();

        private Builder(Class<T> type) {
            this.type = type;
        }

        public Builder<T> table(String table) {
            this.table = table;
            return this;
        }

        /** The primary key: column name, SQL type, and how to read it off the object. */
        public Builder<T> id(String name, String sqlType, Function<T, Object> value) {
            this.id = new Column<>(name, sqlType, true, value);
            return this;
        }

        /**
         * A leading key column, so that the primary key is {@code (partition, id)} and
         * the same id may occur once per partition — a tenant column, typically. With
         * it, {@link #findById(Object, Object)} and {@link #deleteById(Object, Object)}
         * take both values, and the single-argument forms refuse to guess.
         */
        public Builder<T> partitionKey(String name, String sqlType, Function<T, Object> value) {
            this.partition = new Column<>(name, sqlType, true, value);
            return this;
        }

        /** A nullable column kept next to the document because some query filters or sorts on it. */
        public Builder<T> column(String name, String sqlType, Function<T, Object> value) {
            columns.add(new Column<>(name, sqlType, false, value));
            return this;
        }

        /** Like {@link #column}, declared NOT NULL. */
        public Builder<T> requiredColumn(String name, String sqlType, Function<T, Object> value) {
            columns.add(new Column<>(name, sqlType, true, value));
            return this;
        }

        public Builder<T> index(String... columns) {
            indexes.add(new Index(List.of(columns), false));
            return this;
        }

        public Builder<T> uniqueIndex(String... columns) {
            indexes.add(new Index(List.of(columns), true));
            return this;
        }

        public DocumentTable<T> build(Sql sql) {
            if (table == null) throw new IllegalStateException("table() is required");
            if (id == null) throw new IllegalStateException("id() is required");
            this.sql = sql;
            return new DocumentTable<>(this);
        }
    }
}
