package ai.mindconnect.vectorstore.tools;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.vectorstore.embedding.EmbeddingChunk;
import ai.mindconnect.vectorstore.embedding.EmbeddingIndex;
import ai.mindconnect.vectorstore.embedding.EntityRef;
import ai.mindconnect.vectorstore.embedding.EntityType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Reads the vector stores a namespace kept before 0.9 into the embedding
 * index, once. Before, every store had its own storage: the {@code memory}
 * backend a file {@code <baseDir>/<namespace>/vector-stores/<store>.jsonl},
 * pgvector a table {@code vs_<namespace>__<store>}. Their chunks become
 * {@link EntityType#DOCUMENT}s of the store — the old per-store file id is the
 * document's id — and the store lists them.
 *
 * <p>A store that already lists something is not looked at again, so running
 * this twice imports nothing twice. The old files and tables are left as they
 * were. A store whose chunks do not share one dimension is skipped with a
 * warning; the rest go on.
 */
final class LegacyVectorStores {

    private static final Logger log = LoggerFactory.getLogger(LegacyVectorStores.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A chunk as the old stores wrote it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record LegacyChunk(String id, String fileId, int ordinal, String text, Map<String, String> metadata, float[] embedding) {}

    private final Path baseDir;
    /** Where the old {@code vs_*} tables may be; null on files. */
    private final DataSource database;

    LegacyVectorStores(Path baseDir, DataSource database) {
        this.baseDir = baseDir;
        this.database = database;
    }

    /**
     * Imports every old store of the namespace that does not list anything yet.
     *
     * @param index          the index a store's chunks go to — the one its template names
     * @param embeddingModel the index key of a store's model — the store was filled with it
     */
    void importInto(Namespace namespace, VectorStoreRegistry registry, Function<VectorStoreInstance, EmbeddingIndex> index,
                    VectorStoreTemplate defaultTemplate, Function<VectorStoreInstance, String> embeddingModel) {
        Map<String, Source> sources = new LinkedHashMap<>();
        Path dir = baseDir.resolve(namespace.value()).resolve("vector-stores");
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(f -> f.getFileName().toString().endsWith(".jsonl")).sorted().forEach(file -> {
                    String name = file.getFileName().toString();
                    sources.put(name.substring(0, name.length() - ".jsonl".length()), () -> readFile(file));
                });
            } catch (IOException e) {
                log.warn("Cannot list {}: {}", dir, e.getMessage());
            }
        }
        if (database != null) {
            String prefix = "vs_" + namespace.value().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_") + "__";
            Sql sql = Sql.of(database);
            for (String table : sql.query("SELECT table_name FROM information_schema.tables"
                            + " WHERE table_schema = current_schema() AND table_name LIKE ? ESCAPE '\\' ORDER BY table_name",
                    row -> row.string("table_name"), prefix.replace("_", "\\_") + "%")) {
                sources.putIfAbsent(table.substring(prefix.length()), () -> readTable(sql, table));
            }
        }
        for (Map.Entry<String, Source> source : sources.entrySet()) {
            VectorStoreInstance instance = instanceFor(registry, defaultTemplate, source.getKey());
            if (!registry.members(instance.name()).isEmpty()) {
                continue;
            }
            try {
                int documents = importStore(instance, source.getValue().chunks(), registry, index.apply(instance),
                        embeddingModel.apply(instance));
                if (documents > 0) {
                    log.info("Imported vector store '{}' of namespace '{}' ({} document(s)) into the embedding index",
                            instance.name(), namespace.value(), documents);
                }
            } catch (RuntimeException e) {
                log.warn("Vector store '{}' of namespace '{}' was not imported: {}",
                        instance.name(), namespace.value(), e.getMessage());
            }
        }
    }

    private int importStore(VectorStoreInstance instance, List<LegacyChunk> chunks, VectorStoreRegistry registry,
                            EmbeddingIndex index, String embeddingModel) {
        Map<String, List<LegacyChunk>> byFile = new LinkedHashMap<>();
        for (LegacyChunk chunk : chunks) {
            if (chunk.embedding() != null && chunk.embedding().length > 0) {
                byFile.computeIfAbsent(chunk.fileId() == null ? "unknown" : chunk.fileId(), f -> new ArrayList<>()).add(chunk);
            }
        }
        UserId owner = instance.owner() == null ? null : UserId.of(instance.owner());
        String source = VectorStoreRegistry.key(instance.name());
        for (Map.Entry<String, List<LegacyChunk>> file : byFile.entrySet()) {
            EntityRef ref = EntityRef.of(EntityType.DOCUMENT, source, file.getKey());
            List<EmbeddingChunk> embedded = file.getValue().stream()
                    .map(c -> new EmbeddingChunk(c.id(), c.ordinal(), c.text() == null ? "" : c.text(),
                            c.metadata(), c.embedding()))
                    .toList();
            index.replace(ref, owner, "imported", embeddingModel, embedded);
            registry.addMember(instance.name(), ref);
        }
        return byFile.size();
    }

    /**
     * The registered instance behind an old file or table name — those were the
     * store's name made safe — or, for a store nobody registered, one registered
     * now on the default settings; a {@code session-} name is a chat's store.
     */
    private static VectorStoreInstance instanceFor(VectorStoreRegistry registry, VectorStoreTemplate defaultTemplate,
                                                   String stem) {
        Optional<VectorStoreInstance> registered = registry.instance(stem)
                .or(() -> registry.instances().stream()
                        .filter(i -> VectorStoreRegistry.key(i.name()).replaceAll("[^a-z0-9_]", "_")
                                .equals(stem.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_")))
                        .findFirst());
        if (registered.isPresent()) {
            return registered.get();
        }
        boolean chat = stem.startsWith(VectorTools.SESSION_STORE_PREFIX);
        VectorStoreInstance candidate = VectorStoreInstance.fromTemplate(stem, defaultTemplate,
                chat ? VectorStoreInstance.Scope.SESSION : VectorStoreInstance.Scope.GLOBAL,
                chat ? stem.substring(VectorTools.SESSION_STORE_PREFIX.length()) : null);
        return registry.registerInstance(candidate);
    }

    private static List<LegacyChunk> readFile(Path file) {
        List<LegacyChunk> chunks = new ArrayList<>();
        try (MappingIterator<LegacyChunk> it = JSON.readerFor(LegacyChunk.class).readValues(file.toFile())) {
            while (it.hasNext()) {
                chunks.add(it.next());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + file + ": " + e.getMessage(), e);
        }
        return chunks;
    }

    private static List<LegacyChunk> readTable(Sql sql, String table) {
        TypeReference<Map<String, String>> meta = new TypeReference<>() {};
        return sql.query("SELECT chunk_id, file_id, ordinal, content, metadata, embedding::text AS embedding FROM \""
                        + table.replace("\"", "\"\"") + "\"",
                row -> new LegacyChunk(row.string("chunk_id"), row.string("file_id"), row.integer("ordinal"),
                        row.string("content"), row.json("metadata", meta), parseVector(row.string("embedding"))));
    }

    private static float[] parseVector(String text) {
        String inner = text.strip();
        inner = inner.substring(1, inner.length() - 1);
        if (inner.isBlank()) {
            return new float[0];
        }
        String[] parts = inner.split(",");
        float[] vector = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            vector[i] = Float.parseFloat(parts[i].strip());
        }
        return vector;
    }

    @FunctionalInterface
    private interface Source {
        List<LegacyChunk> chunks();
    }
}
