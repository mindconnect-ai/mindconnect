package ai.mindconnect.vectorstore.memory;

import ai.mindconnect.vectorstore.VectorChunk;
import ai.mindconnect.vectorstore.VectorStore;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One store of the memory backend. Persistence is a JSONL file (one chunk per
 * line, embedding included) rewritten atomically on every mutation; search
 * runs over vectors held on the heap, normalised at load time so cosine
 * similarity is a plain dot product. {@link #unloadIfIdleSince} drops the
 * heap copy — the file stays, the next access reloads.
 */
final class MemoryVectorStore implements VectorStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String id;
    private final Path file;
    private final int maxChunks;

    /** Loaded chunks by id, vectors normalised; {@code null} = not loaded. */
    private Map<String, VectorChunk> loaded;
    private long lastUsedMs;

    MemoryVectorStore(String id, Path file, int maxChunks) {
        this.id = id;
        this.file = file;
        this.maxChunks = maxChunks;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public synchronized void upsert(List<VectorChunk> chunks) {
        Map<String, VectorChunk> current = ensureLoaded();
        Integer dimension = current.values().stream().findFirst()
                .map(c -> c.embedding().length).orElse(null);
        for (VectorChunk chunk : chunks) {
            if (chunk.embedding() == null || chunk.embedding().length == 0) {
                throw new IllegalArgumentException("Chunk '" + chunk.id() + "' has no embedding");
            }
            if (dimension == null) {
                dimension = chunk.embedding().length;
            } else if (chunk.embedding().length != dimension) {
                throw new IllegalArgumentException("Chunk '" + chunk.id() + "' has dimension "
                        + chunk.embedding().length + ", store '" + id + "' uses " + dimension);
            }
            current.put(chunk.id(), normalised(chunk));
        }
        if (current.size() > maxChunks) {
            throw new IllegalStateException("Vector store '" + id + "' would exceed its memory-backend "
                    + "limit of " + maxChunks + " chunks — use a server-side backend (pgvector) for "
                    + "corpora of this size");
        }
        persist(current);
    }

    @Override
    public synchronized List<SearchHit> search(float[] queryEmbedding, int topK) {
        Map<String, VectorChunk> current = ensureLoaded();
        float[] query = normalised(queryEmbedding.clone());
        List<SearchHit> hits = new ArrayList<>();
        for (VectorChunk chunk : current.values()) {
            hits.add(new SearchHit(chunk, dot(query, chunk.embedding())));
        }
        hits.sort(Comparator.comparingDouble(SearchHit::score).reversed());
        return hits.size() > topK ? List.copyOf(hits.subList(0, topK)) : hits;
    }

    @Override
    public synchronized void deleteFile(String fileId) {
        Map<String, VectorChunk> current = ensureLoaded();
        if (current.values().removeIf(c -> fileId.equals(c.fileId()))) {
            persist(current);
        }
    }

    @Override
    public synchronized long chunkCount() {
        return ensureLoaded().size();
    }

    @Override
    public synchronized Map<String, Long> listFiles() {
        Map<String, Long> files = new LinkedHashMap<>();
        for (VectorChunk chunk : ensureLoaded().values()) {
            files.merge(chunk.fileId(), 1L, Long::sum);
        }
        return files;
    }

    /** Drops the heap copy when unused since {@code cutoffMs}; file stays. */
    synchronized boolean unloadIfIdleSince(long cutoffMs) {
        if (loaded == null || lastUsedMs >= cutoffMs) {
            return false;
        }
        loaded = null;
        return true;
    }

    // ── loading & persistence ──────────────────────────────────────────────

    private Map<String, VectorChunk> ensureLoaded() {
        lastUsedMs = System.currentTimeMillis();
        if (loaded != null) {
            return loaded;
        }
        Map<String, VectorChunk> chunks = new LinkedHashMap<>();
        if (Files.exists(file)) {
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) continue;
                    VectorChunk chunk = MAPPER.readValue(line, VectorChunk.class);
                    chunks.put(chunk.id(), normalised(chunk));
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Could not load vector store file " + file, e);
            }
        }
        loaded = chunks;
        return chunks;
    }

    /** Atomic rewrite: temp file next to the target, then move-with-replace. */
    private void persist(Map<String, VectorChunk> chunks) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (BufferedWriter writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                for (VectorChunk chunk : chunks.values()) {
                    writer.write(MAPPER.writeValueAsString(chunk));
                    writer.newLine();
                }
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not persist vector store file " + file, e);
        }
    }

    // ── vector math ────────────────────────────────────────────────────────

    private static VectorChunk normalised(VectorChunk chunk) {
        return new VectorChunk(chunk.id(), chunk.fileId(), chunk.ordinal(), chunk.text(),
                chunk.metadata(), normalised(chunk.embedding().clone()));
    }

    private static float[] normalised(float[] vector) {
        double norm = 0;
        for (float v : vector) norm += v * v;
        norm = Math.sqrt(norm);
        if (norm == 0) return vector;
        for (int i = 0; i < vector.length; i++) vector[i] /= (float) norm;
        return vector;
    }

    private static double dot(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) sum += a[i] * b[i];
        return sum;
    }
}
