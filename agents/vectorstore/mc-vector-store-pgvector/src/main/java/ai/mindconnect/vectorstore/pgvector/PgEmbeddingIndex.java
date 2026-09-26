package ai.mindconnect.vectorstore.pgvector;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.jdbc.Row;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.vectorstore.embedding.EmbeddingChecks;
import ai.mindconnect.vectorstore.embedding.EmbeddingChunk;
import ai.mindconnect.vectorstore.embedding.EmbeddingIndex;
import ai.mindconnect.vectorstore.embedding.EmbeddingQuery;
import ai.mindconnect.vectorstore.embedding.EntityRef;
import ai.mindconnect.vectorstore.embedding.EntityType;
import ai.mindconnect.vectorstore.embedding.MetadataField;
import ai.mindconnect.vectorstore.embedding.MetadataFields;
import ai.mindconnect.vectorstore.embedding.MetadataFilter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * {@link EmbeddingIndex} on pgvector: the entries of every entity of every
 * namespace in one table — {@value #DEFAULT_TABLE} unless the index names another — bound to one namespace per
 * instance.
 *
 * <p><b>Search is filter first, rank second.</b> The candidates are selected
 * in a {@code MATERIALIZED} CTE — by ref over the primary key
 * {@code (namespace, entity_type, source, container, entity_id, …)}, by owner
 * over {@code (namespace, owner_id, entity_type, source, container)}, by
 * metadata equality over the GIN index on {@code meta}, by a declared field
 * over its own index — and only then is the cosine distance computed, exactly,
 * over those. There is deliberately no HNSW index: with one, the planner may
 * take the whole table's nearest neighbours and filter afterwards, returning
 * too few hits — or none — for a selection that is a small share of the table.
 * An exact scan is cheap up to some tens of thousands of chunks per search.
 *
 * <p>A declared {@link MetadataField} is kept in {@value #FIELDS} and gets a
 * partial expression index on {@code (namespace, meta->>'key')} for its entity
 * type — a number cast to {@code numeric}, a timestamp as its fixed UTC text,
 * which sorts the way the instants do.
 *
 * <p>The {@code embedding} column has no fixed dimension, so models of
 * different sizes share the table; {@code dimension} is stored beside it and a
 * search compares only chunks of the query's dimension.
 *
 * <p>Writes to one entity are serialised with a transaction-scoped advisory
 * lock, so two concurrent re-indexes of the same entity leave one of them, not
 * both. Needs PostgreSQL 12 or newer with the {@code vector} extension; the
 * first use checks the version and says so, and creates the tables. Deleting a
 * namespace needs nothing extra: the namespace purge clears every table with a
 * {@code namespace} column.
 */
public final class PgEmbeddingIndex implements EmbeddingIndex {

    /** The table of an index that names none. */
    public static final String DEFAULT_TABLE = "mc_embedding";
    static final String TABLE = DEFAULT_TABLE;

    private static final java.util.regex.Pattern TABLE_NAME = java.util.regex.Pattern.compile("[a-z_][a-z0-9_]{0,47}");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> META = new TypeReference<>() {};

    /** Serialises concurrent first uses across JVMs; any constant key will do. */
    private static final long SCHEMA_LOCK = 0x6d63_656d_6265_6464L;

    /** {@code AS MATERIALIZED} needs 12; {@code hashtextextended} 11. */
    private static final int MIN_SERVER_VERSION = 120000;

    private static final String KEY = "namespace = ? AND entity_type = ? AND source = ? AND container = ? AND entity_id = ?";

    private final Sql sql;
    private final String namespace;
    /** This index's table, and beside it {@code <table>_field} for the declared fields. */
    private final String table;
    private final String fieldsTable;
    /** The declared fields as last read from {@value #FIELDS}; other processes may declare more. */
    private volatile MetadataFields fields = new MetadataFields();

    private volatile boolean schemaReady;

    public PgEmbeddingIndex(DataSource dataSource, Namespace namespace) {
        this(Sql.of(dataSource), namespace, DEFAULT_TABLE);
    }

    public PgEmbeddingIndex(Sql sql, Namespace namespace) {
        this(sql, namespace, DEFAULT_TABLE);
    }

    /**
     * An index in {@code table} — one table per index definition, so chat uploads
     * or one large knowledge base can live apart from the rest.
     *
     * @throws IllegalArgumentException for a table name that is not lower case letters, digits and '_'
     */
    public PgEmbeddingIndex(Sql sql, Namespace namespace, String table) {
        this.sql = Objects.requireNonNull(sql, "sql");
        this.namespace = Objects.requireNonNull(namespace, "namespace").value();
        if (table == null || !TABLE_NAME.matcher(table).matches()) {
            throw new IllegalArgumentException("A table name is lower case letters, digits and '_', at most 48: '"
                    + table + "'");
        }
        this.table = table;
        this.fieldsTable = table + "_field";
    }

    public PgEmbeddingIndex(DataSource dataSource, Namespace namespace, String table) {
        this(Sql.of(dataSource), namespace, table);
    }

    /** The table this index keeps its entries in. */
    public String table() {
        return table;
    }

    /**
     * Makes sure the database has the {@code vector} extension, by
     * {@code CREATE EXTENSION IF NOT EXISTS vector}.
     *
     * @return empty when the extension is there; otherwise the database's reason —
     *         typically that the server has no pgvector at all (a plain
     *         {@code postgres} image), or that the user may not create it
     */
    public static Optional<String> enableExtension(DataSource dataSource) {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS vector");
            return Optional.empty();
        } catch (SQLException e) {
            return Optional.of(e.getMessage());
        }
    }

    @Override
    public void replace(EntityRef ref, UserId owner, String version, String embeddingModel,
                        List<EmbeddingChunk> chunks) {
        EmbeddingChecks.checkReplace(ref, version, embeddingModel, chunks);
        Sql db = ready();
        String[] ids = new String[chunks.size()];
        Integer[] ordinals = new Integer[chunks.size()];
        String[] contents = new String[chunks.size()];
        String[] metas = new String[chunks.size()];
        String[] vectors = new String[chunks.size()];
        for (int i = 0; i < chunks.size(); i++) {
            EmbeddingChunk chunk = chunks.get(i);
            ids[i] = chunk.id();
            ordinals[i] = chunk.ordinal();
            contents[i] = chunk.text();
            metas[i] = json(fields.normalise(ref.type(), chunk.metadata()));
            vectors[i] = vectorLiteral(chunk.embedding());
        }
        int dimension = chunks.isEmpty() ? 0 : chunks.get(0).embedding().length;
        db.inTransaction(tx -> {
            lock(tx, ref);
            tx.update("DELETE FROM " + table + " WHERE " + KEY + " AND embedding_model = ?",
                    keyParams(ref, embeddingModel));
            if (chunks.isEmpty()) {
                return;
            }
            // One statement for all chunks: the arrays are unnested into rows.
            tx.update("INSERT INTO " + table + " (namespace, entity_type, source, container, entity_id,"
                            + " embedding_model, chunk_id, owner_id, version, ordinal, content, meta, dimension, embedding)"
                            + " SELECT ?, ?, ?, ?, ?, ?, c.chunk_id, ?::text, ?, c.ordinal, c.content, c.meta::jsonb, ?,"
                            + " c.embedding::vector"
                            + " FROM unnest(?::text[], ?::int[], ?::text[], ?::text[], ?::text[])"
                            + " AS c(chunk_id, ordinal, content, meta, embedding)",
                    namespace, ref.type().value(), ref.source(), ref.container(), ref.id(), embeddingModel,
                    owner == null ? null : owner.value(), version, dimension,
                    ids, ordinals, contents, metas, vectors);
        });
    }

    @Override
    public void delete(EntityRef ref) {
        ready().inTransaction(tx -> {
            lock(tx, ref);
            tx.update("DELETE FROM " + table + " WHERE " + KEY, keyParams(ref));
        });
    }

    @Override
    public void relocate(EntityRef from, EntityRef to) {
        EmbeddingChecks.checkRelocate(from, to);
        if (from.equals(to)) {
            return;
        }
        ready().inTransaction(tx -> {
            // Both locks, always in the same order, so two opposite moves cannot deadlock.
            Stream.of(from, to).map(this::lockKey).sorted()
                    .forEach(key -> tx.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", row -> 1, key));
            // Nothing indexed under `from`: leave `to` alone — it may already
            // have been indexed at its new place.
            long moving = tx.scalar("SELECT count(*) FROM " + table + " WHERE " + KEY, Long.class, keyParams(from));
            if (moving == 0) {
                return;
            }
            tx.update("DELETE FROM " + table + " WHERE " + KEY, keyParams(to));
            tx.update("UPDATE " + table + " SET container = ?, entity_id = ? WHERE " + KEY,
                    concat(new Object[]{to.container(), to.id()}, keyParams(from)));
        });
    }

    @Override
    public Optional<String> indexedVersion(EntityRef ref, String embeddingModel) {
        return ready().query("SELECT version FROM " + table + " WHERE " + KEY + " AND embedding_model = ? LIMIT 1",
                row -> row.string("version"), keyParams(ref, embeddingModel)).stream().findFirst();
    }

    @Override
    public long chunkCount(EntityRef ref, String embeddingModel) {
        return ready().scalar("SELECT count(*) FROM " + table + " WHERE " + KEY + " AND embedding_model = ?",
                Long.class, keyParams(ref, embeddingModel));
    }

    @Override
    public List<EmbeddingHit> search(EmbeddingQuery query, float[] queryEmbedding, int topK) {
        EmbeddingChecks.requireUsable(queryEmbedding, "The query");
        query.requireOwnerDecision();
        Sql db = ready();
        Where where = where(query);
        if (topK <= 0 || where == null) {
            return List.of();
        }
        where.and("dimension = ?", queryEmbedding.length);
        List<Object> params = new ArrayList<>(where.params);
        params.add(vectorLiteral(queryEmbedding));
        params.add(topK);
        return db.query("WITH candidates AS MATERIALIZED ("
                        + " SELECT entity_type, source, container, entity_id, owner_id, chunk_id, ordinal, content, meta,"
                        + " embedding FROM " + table + " WHERE " + where.sql()
                        + ") SELECT entity_type, source, container, entity_id, owner_id, chunk_id, ordinal, content,"
                        + " meta, embedding <=> ?::vector AS distance"
                        + " FROM candidates ORDER BY distance LIMIT ?",
                PgEmbeddingIndex::hit, params.toArray());
    }

    @Override
    public List<IndexedEntity> list(EmbeddingQuery query) {
        query.requireOwnerDecision();
        Sql db = ready();
        Where where = where(query);
        if (where == null) {
            return List.of();
        }
        return db.query("SELECT entity_type, source, container, entity_id, owner_id, embedding_model,"
                        + " max(version) AS version, count(*) AS chunks FROM " + table + " WHERE " + where.sql()
                        + " GROUP BY entity_type, source, container, entity_id, owner_id, embedding_model"
                        + " ORDER BY entity_type, source, container, entity_id",
                row -> new IndexedEntity(ref(row), owner(row), row.string("embedding_model"),
                        row.string("version"), row.longValue("chunks")),
                where.params.toArray());
    }

    @Override
    public ChunkPage chunks(EmbeddingQuery query, String text, int offset, int limit) {
        query.requireOwnerDecision();
        Sql db = ready();
        Where where = where(query);
        if (where == null) {
            return new ChunkPage(List.of(), 0);
        }
        if (text != null && !text.isBlank()) {
            // Taken literally: the LIKE wildcards in the search text are escaped.
            where.and("content ILIKE ? ESCAPE '\\'",
                    "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        long total = db.scalar("SELECT count(*) FROM " + table + " WHERE " + where.sql(), Long.class,
                where.params.toArray());
        if (limit <= 0) {
            return new ChunkPage(List.of(), total);
        }
        List<Object> params = new ArrayList<>(where.params);
        params.add(limit);
        params.add(Math.max(offset, 0));
        List<IndexedChunk> page = db.query("SELECT entity_type, source, container, entity_id, chunk_id, ordinal,"
                        + " content, meta FROM " + table + " WHERE " + where.sql()
                        + " ORDER BY entity_type, source, container, entity_id, ordinal LIMIT ? OFFSET ?",
                row -> new IndexedChunk(ref(row), new EmbeddingChunk(row.string("chunk_id"), row.integer("ordinal"),
                        row.string("content"), row.json("meta", META), new float[0])),
                params.toArray());
        return new ChunkPage(page, total);
    }

    @Override
    public void declareField(MetadataField field) {
        Sql db = ready();
        db.inTransaction(tx -> {
            tx.query("SELECT pg_advisory_xact_lock(" + SCHEMA_LOCK + ")", row -> 1);
            tx.update("INSERT INTO " + fieldsTable + " (entity_type, key, kind) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                    field.type().value(), field.key(), field.kind().name());
            String kind = tx.scalar("SELECT kind FROM " + fieldsTable + " WHERE entity_type = ? AND key = ?",
                    String.class, field.type().value(), field.key());
            if (!field.kind().name().equals(kind)) {
                throw new IllegalArgumentException("Metadata '" + field.key() + "' of " + field.type()
                        + " is declared as " + kind + ", not " + field.kind());
            }
            // Type and key are checked patterns (lower case, digits, '_', '-', '.'), safe as literals.
            tx.execute("CREATE INDEX IF NOT EXISTS " + indexName(field) + " ON " + table
                    + " (namespace, " + expression(field) + ") WHERE entity_type = '" + field.type().value() + "'");
        });
        loadFields(db);
    }

    @Override
    public List<MetadataField> fields() {
        ready();
        return fields.all();
    }

    // ── query → SQL ────────────────────────────────────────────────────────

    /** The candidate selection; null when nothing can pass (an empty ref list). */
    private Where where(EmbeddingQuery query) {
        if (query.refs() != null && query.refs().isEmpty()) {
            return null;
        }
        List<MetadataFields.ResolvedFilter> filters = resolve(query);
        Where where = new Where();
        where.and("namespace = ?", namespace);
        where.and("embedding_model = ?", query.embeddingModel());
        if (query.refs() != null) {
            List<EntityRef> refs = List.copyOf(query.refs());
            where.and("(entity_type, source, container, entity_id) IN"
                            + " (SELECT * FROM unnest(?::text[], ?::text[], ?::text[], ?::text[]))",
                    refs.stream().map(r -> r.type().value()).toArray(String[]::new),
                    refs.stream().map(EntityRef::source).toArray(String[]::new),
                    refs.stream().map(EntityRef::container).toArray(String[]::new),
                    refs.stream().map(EntityRef::id).toArray(String[]::new));
        }
        if (!query.types().isEmpty()) {
            // A lone array argument would be spread over the varargs; (Object) keeps it one parameter.
            where.and("entity_type = ANY(?::text[])", (Object) query.types().stream().map(EntityType::value).toArray(String[]::new));
        }
        if (!query.owners().isEmpty()) {
            where.and(query.shared() ? "(owner_id = ANY(?::text[]) OR owner_id IS NULL)" : "owner_id = ANY(?::text[])",
                    (Object) query.owners().stream().map(UserId::value).toArray(String[]::new));
        } else if (query.shared()) {
            where.and("owner_id IS NULL");
        }
        if (query.source() != null) {
            where.and("source = ?", query.source());
        }
        if (query.container() != null) {
            where.and("container = ?", query.container());
        }
        Map<String, String> equal = new LinkedHashMap<>();
        Set<EntityType> reachable = reachable(query);
        for (MetadataFields.ResolvedFilter resolved : filters) {
            MetadataFilter filter = resolved.filter();
            if (filter.op() == MetadataFilter.Op.EQ) {
                String earlier = equal.putIfAbsent(filter.key(), filter.value());
                if (earlier != null && !earlier.equals(filter.value())) {
                    where.and("false");   // one key, two different values: nothing passes
                }
                continue;
            }
            // Per entity type, so the planner can use that type's partial index.
            List<String> perType = new ArrayList<>();
            List<Object> values = new ArrayList<>();
            for (EntityType type : reachable) {
                MetadataField field = fields.field(type, filter.key()).orElseThrow();
                String column = expression(field);
                String cast = field.kind() == MetadataField.Kind.NUMBER ? "::numeric" : "";
                String comparison = switch (filter.op()) {
                    case IN -> column + " = ANY(?::" + (cast.isEmpty() ? "text" : "numeric") + "[])";
                    case GTE -> column + " >= ?" + cast;
                    case LTE -> column + " <= ?" + cast;
                    case EQ -> throw new IllegalStateException();
                };
                perType.add("(entity_type = '" + type.value() + "' AND " + comparison + ")");
                values.add(filter.op() == MetadataFilter.Op.IN ? filter.values().toArray(String[]::new) : filter.value());
            }
            where.and("(" + String.join(" OR ", perType) + ")", values.toArray());
        }
        if (!equal.isEmpty()) {
            where.and("meta @> ?::jsonb", json(equal));
        }
        return where;
    }

    /** The filters checked against the declared fields — read again once when one is unknown here. */
    private List<MetadataFields.ResolvedFilter> resolve(EmbeddingQuery query) {
        try {
            return fields.resolve(query);
        } catch (IllegalArgumentException e) {
            loadFields(sql);
            return fields.resolve(query);
        }
    }

    private static Set<EntityType> reachable(EmbeddingQuery query) {
        if (!query.types().isEmpty()) {
            return query.types();
        }
        return query.refs() == null ? Set.of() : query.refs().stream().map(EntityRef::type).collect(Collectors.toSet());
    }

    private static String expression(MetadataField field) {
        String text = "(meta->>'" + field.key() + "')";
        return field.kind() == MetadataField.Kind.NUMBER ? "(" + text + "::numeric)" : text;
    }

    /** At most 63 characters, and unique per type and key even where sanitising would collide. */
    private String indexName(MetadataField field) {
        String readable = (field.type().value() + "_" + field.key()).replaceAll("[^a-z0-9_]", "_");
        String hash = sha256(table + "\u001f" + field.type().value() + "\u001f" + field.key()).substring(0, 8);
        String name = table + "_f_" + readable;
        return (name.length() > 54 ? name.substring(0, 54) : name) + "_" + hash;
    }

    /** Conditions joined by AND, with their parameters in order. */
    private static final class Where {
        private final List<String> conditions = new ArrayList<>();
        private final List<Object> params = new ArrayList<>();

        void and(String condition, Object... values) {
            conditions.add(condition);
            params.addAll(List.of(values));
        }

        String sql() {
            return String.join(" AND ", conditions);
        }
    }

    // ── plumbing ───────────────────────────────────────────────────────────

    private static EmbeddingHit hit(Row row) throws SQLException {
        // Vectors are not read back — the searcher needs text and provenance.
        EmbeddingChunk chunk = new EmbeddingChunk(row.string("chunk_id"), row.integer("ordinal"),
                row.string("content"), row.json("meta", META), new float[0]);
        return new EmbeddingHit(ref(row), owner(row), chunk, 1 - row.raw().getDouble("distance"));
    }

    private static EntityRef ref(Row row) throws SQLException {
        return new EntityRef(EntityType.of(row.string("entity_type")), row.string("source"),
                row.string("container"), row.string("entity_id"));
    }

    private static UserId owner(Row row) throws SQLException {
        String owner = row.string("owner_id");
        return owner == null ? null : UserId.of(owner);
    }

    private void lock(Sql tx, EntityRef ref) {
        tx.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", row -> 1, lockKey(ref));
    }

    private String lockKey(EntityRef ref) {
        return String.join("\u001f", table, namespace, ref.type().value(), ref.source(), ref.container(), ref.id());
    }

    private Object[] keyParams(EntityRef ref, Object... more) {
        return concat(new Object[]{namespace, ref.type().value(), ref.source(), ref.container(), ref.id()}, more);
    }

    private static Object[] concat(Object[] first, Object[] second) {
        Object[] all = new Object[first.length + second.length];
        System.arraycopy(first, 0, all, 0, first.length);
        System.arraycopy(second, 0, all, first.length, second.length);
        return all;
    }

    private static String json(Map<String, String> map) {
        try {
            return MAPPER.writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalArgumentException("Metadata is not serialisable: " + e.getMessage(), e);
        }
    }

    /** pgvector's text form of a vector: {@code [0.1,0.2,…]}. */
    static String vectorLiteral(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(embedding[i]);
        }
        return sb.append(']').toString();
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void loadFields(Sql db) {
        MetadataFields loaded = new MetadataFields();
        db.query("SELECT entity_type, key, kind FROM " + fieldsTable,
                row -> new MetadataField(EntityType.of(row.string("entity_type")), row.string("key"),
                        MetadataField.Kind.valueOf(row.string("kind"))))
                .forEach(loaded::declare);
        fields = loaded;
    }

    private Sql ready() {
        if (!schemaReady) {
            createSchema();
        }
        return sql;
    }

    private synchronized void createSchema() {
        if (schemaReady) {
            return;
        }
        int version = sql.scalar("SELECT current_setting('server_version_num')::int", Integer.class);
        if (version < MIN_SERVER_VERSION) {
            throw new IllegalStateException("The embedding index needs PostgreSQL 12 or newer ("
                    + "MATERIALIZED CTEs, hashtextextended); this server is " + version);
        }
        sql.inTransaction(tx -> {
            // Concurrent CREATE ... IF NOT EXISTS can still collide in the
            // catalogue; the transaction-scoped lock makes first use serial.
            tx.query("SELECT pg_advisory_xact_lock(" + SCHEMA_LOCK + ")", row -> 1);
            tx.execute("CREATE EXTENSION IF NOT EXISTS vector;"
                    + " CREATE TABLE IF NOT EXISTS " + table + " ("
                    + "  namespace text NOT NULL,"
                    + "  entity_type text NOT NULL,"
                    + "  source text NOT NULL,"
                    + "  container text NOT NULL DEFAULT '',"
                    + "  entity_id text NOT NULL,"
                    + "  embedding_model text NOT NULL,"
                    + "  chunk_id text NOT NULL,"
                    + "  owner_id text,"
                    + "  version text NOT NULL,"
                    + "  ordinal int NOT NULL,"
                    + "  content text NOT NULL,"
                    + "  meta jsonb NOT NULL DEFAULT '{}',"
                    + "  dimension int NOT NULL,"
                    + "  embedding vector NOT NULL,"
                    + "  indexed_at timestamptz NOT NULL DEFAULT now(),"
                    + "  PRIMARY KEY (namespace, entity_type, source, container, entity_id, embedding_model, chunk_id));"
                    + " CREATE INDEX IF NOT EXISTS " + table + "_owner_idx ON " + table
                    + "  (namespace, owner_id, entity_type, source, container);"
                    + " CREATE INDEX IF NOT EXISTS " + table + "_meta_idx ON " + table
                    + "  USING gin (meta jsonb_path_ops);"
                    + " CREATE TABLE IF NOT EXISTS " + fieldsTable + " ("
                    + "  entity_type text NOT NULL,"
                    + "  key text NOT NULL,"
                    + "  kind text NOT NULL,"
                    + "  PRIMARY KEY (entity_type, key))");
        });
        loadFields(sql);
        schemaReady = true;
    }
}
