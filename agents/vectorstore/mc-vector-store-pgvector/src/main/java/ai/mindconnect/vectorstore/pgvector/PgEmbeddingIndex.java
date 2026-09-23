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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * {@link EmbeddingIndex} on pgvector: the entries of every entity of every
 * namespace in the one table {@value #TABLE}, bound to one namespace per
 * instance.
 *
 * <p><b>Search is filter first, rank second.</b> The candidates are selected
 * in a {@code MATERIALIZED} CTE — by ref over the primary key
 * {@code (namespace, entity_type, source, container, entity_id, …)}, by owner
 * over {@code (namespace, owner_id, entity_type, source, container)}, by
 * metadata over the GIN index — and only then is the cosine distance computed,
 * exactly, over those. There is deliberately no HNSW index: with one, the
 * planner may take the whole table's nearest neighbours and filter afterwards,
 * returning too few hits — or none — for a selection that is a small share of
 * the table. An exact scan is cheap up to some tens of thousands of chunks per
 * search.
 *
 * <p>The {@code embedding} column has no fixed dimension, so models of
 * different sizes share the table; {@code dimension} is stored beside it and a
 * search compares only chunks of the query's dimension.
 *
 * <p>Writes to one entity are serialised with a transaction-scoped advisory
 * lock, so two concurrent re-indexes of the same entity leave one of them, not
 * both. The table is created on first use. Deleting a namespace needs nothing
 * extra: the namespace purge clears every table with a {@code namespace} column.
 */
public final class PgEmbeddingIndex implements EmbeddingIndex {

    static final String TABLE = "mc_embedding";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> META = new TypeReference<>() {};

    /** Serialises concurrent first uses across JVMs; any constant key will do. */
    private static final long SCHEMA_LOCK = 0x6d63_656d_6265_6464L;

    private static final String KEY = "namespace = ? AND entity_type = ? AND source = ? AND container = ? AND entity_id = ?";

    private final Sql sql;
    private final String namespace;

    private volatile boolean schemaReady;

    public PgEmbeddingIndex(DataSource dataSource, Namespace namespace) {
        this(Sql.of(dataSource), namespace);
    }

    public PgEmbeddingIndex(Sql sql, Namespace namespace) {
        this.sql = Objects.requireNonNull(sql, "sql");
        this.namespace = Objects.requireNonNull(namespace, "namespace").value();
    }

    @Override
    public void replace(EntityRef ref, UserId owner, String version, String embeddingModel,
                        List<EmbeddingChunk> chunks) {
        EmbeddingChecks.checkReplace(ref, version, embeddingModel, chunks);
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
            metas[i] = json(chunk.metadata());
            vectors[i] = PgVectorStore.vectorLiteral(chunk.embedding());
        }
        int dimension = chunks.isEmpty() ? 0 : chunks.get(0).embedding().length;
        ready().inTransaction(tx -> {
            lock(tx, ref);
            tx.update("DELETE FROM " + TABLE + " WHERE " + KEY + " AND embedding_model = ?",
                    keyParams(ref, embeddingModel));
            if (chunks.isEmpty()) {
                return;
            }
            // One statement for all chunks: the arrays are unnested into rows.
            tx.update("INSERT INTO " + TABLE + " (namespace, entity_type, source, container, entity_id,"
                            + " embedding_model, chunk_id, owner_id, version, ordinal, content, meta, dimension, embedding)"
                            + " SELECT ?, ?, ?, ?, ?, ?, c.chunk_id, ?::text, ?, c.ordinal, c.content, c.meta::jsonb, ?,"
                            + " c.embedding::vector"
                            + " FROM unnest(?::text[], ?::int[], ?::text[], ?::text[], ?::text[])"
                            + " AS c(chunk_id, ordinal, content, meta, embedding)",
                    namespace, ref.type(), ref.source(), ref.container(), ref.id(), embeddingModel,
                    owner == null ? null : owner.value(), version, dimension,
                    ids, ordinals, contents, metas, vectors);
        });
    }

    @Override
    public void delete(EntityRef ref) {
        ready().inTransaction(tx -> {
            lock(tx, ref);
            tx.update("DELETE FROM " + TABLE + " WHERE " + KEY, keyParams(ref));
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
            tx.update("DELETE FROM " + TABLE + " WHERE " + KEY, keyParams(to));
            tx.update("UPDATE " + TABLE + " SET container = ?, entity_id = ? WHERE " + KEY,
                    concat(new Object[]{to.container(), to.id()}, keyParams(from)));
        });
    }

    @Override
    public Optional<String> indexedVersion(EntityRef ref, String embeddingModel) {
        return ready().query("SELECT version FROM " + TABLE + " WHERE " + KEY + " AND embedding_model = ? LIMIT 1",
                row -> row.string("version"), keyParams(ref, embeddingModel)).stream().findFirst();
    }

    @Override
    public long chunkCount(EntityRef ref, String embeddingModel) {
        return ready().scalar("SELECT count(*) FROM " + TABLE + " WHERE " + KEY + " AND embedding_model = ?",
                Long.class, keyParams(ref, embeddingModel));
    }

    @Override
    public List<EmbeddingHit> search(EmbeddingQuery query, float[] queryEmbedding, int topK) {
        EmbeddingChecks.requireNonZero(queryEmbedding, "The query");
        if (topK <= 0 || (query.refs() != null && query.refs().isEmpty())) {
            return List.of();
        }
        List<String> where = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        where.add("namespace = ?");
        params.add(namespace);
        where.add("embedding_model = ?");
        params.add(query.embeddingModel());
        where.add("dimension = ?");
        params.add(queryEmbedding.length);
        if (query.refs() != null) {
            List<EntityRef> refs = List.copyOf(query.refs());
            where.add("(entity_type, source, container, entity_id) IN"
                    + " (SELECT * FROM unnest(?::text[], ?::text[], ?::text[], ?::text[]))");
            params.add(refs.stream().map(EntityRef::type).toArray(String[]::new));
            params.add(refs.stream().map(EntityRef::source).toArray(String[]::new));
            params.add(refs.stream().map(EntityRef::container).toArray(String[]::new));
            params.add(refs.stream().map(EntityRef::id).toArray(String[]::new));
        }
        if (!query.types().isEmpty()) {
            where.add("entity_type = ANY(?::text[])");
            params.add(query.types().toArray(String[]::new));
        }
        if (!query.owners().isEmpty()) {
            where.add(query.shared() ? "(owner_id = ANY(?::text[]) OR owner_id IS NULL)" : "owner_id = ANY(?::text[])");
            params.add(query.ownerIds().toArray(String[]::new));
        } else if (query.shared()) {
            where.add("owner_id IS NULL");
        }
        if (query.source() != null) {
            where.add("source = ?");
            params.add(query.source());
        }
        if (query.container() != null) {
            where.add("container = ?");
            params.add(query.container());
        }
        if (!query.metadata().isEmpty()) {
            where.add("meta @> ?::jsonb");
            params.add(json(query.metadata()));
        }
        params.add(PgVectorStore.vectorLiteral(queryEmbedding));
        params.add(topK);
        return ready().query("WITH candidates AS MATERIALIZED ("
                        + " SELECT entity_type, source, container, entity_id, owner_id, chunk_id, ordinal, content, meta,"
                        + " embedding FROM " + TABLE + " WHERE " + String.join(" AND ", where)
                        + ") SELECT entity_type, source, container, entity_id, owner_id, chunk_id, ordinal, content,"
                        + " meta, embedding <=> ?::vector AS distance"
                        + " FROM candidates ORDER BY distance LIMIT ?",
                PgEmbeddingIndex::hit, params.toArray());
    }

    // ── plumbing ───────────────────────────────────────────────────────────

    private static EmbeddingHit hit(Row row) throws SQLException {
        String owner = row.string("owner_id");
        // Vectors are not read back — the searcher needs text and provenance.
        EmbeddingChunk chunk = new EmbeddingChunk(row.string("chunk_id"), row.integer("ordinal"),
                row.string("content"), row.json("meta", META), new float[0]);
        return new EmbeddingHit(
                new EntityRef(row.string("entity_type"), row.string("source"), row.string("container"),
                        row.string("entity_id")),
                owner == null ? null : UserId.of(owner),
                chunk,
                1 - row.raw().getDouble("distance"));
    }

    private void lock(Sql tx, EntityRef ref) {
        tx.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", row -> 1, lockKey(ref));
    }

    private String lockKey(EntityRef ref) {
        return String.join("\u001f", TABLE, namespace, ref.type(), ref.source(), ref.container(), ref.id());
    }

    private Object[] keyParams(EntityRef ref, Object... more) {
        return concat(new Object[]{namespace, ref.type(), ref.source(), ref.container(), ref.id()}, more);
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
        sql.inTransaction(tx -> {
            // Concurrent CREATE ... IF NOT EXISTS can still collide in the
            // catalogue; the transaction-scoped lock makes first use serial.
            tx.query("SELECT pg_advisory_xact_lock(" + SCHEMA_LOCK + ")", row -> 1);
            tx.execute("CREATE EXTENSION IF NOT EXISTS vector;"
                    + " CREATE TABLE IF NOT EXISTS " + TABLE + " ("
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
                    + " CREATE INDEX IF NOT EXISTS " + TABLE + "_owner_idx ON " + TABLE
                    + "  (namespace, owner_id, entity_type, source, container);"
                    + " CREATE INDEX IF NOT EXISTS " + TABLE + "_meta_idx ON " + TABLE
                    + "  USING gin (meta jsonb_path_ops)");
        });
        schemaReady = true;
    }
}
