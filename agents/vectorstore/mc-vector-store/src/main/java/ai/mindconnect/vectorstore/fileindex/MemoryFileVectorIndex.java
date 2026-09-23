package ai.mindconnect.vectorstore.fileindex;

import ai.mindconnect.filestore.FileId;
import ai.mindconnect.vectorstore.VectorChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link FileVectorIndex} on the heap, without persistence — for tests and for
 * embedding hosts that re-index on start. Searches the same way the Postgres
 * index does: select the given files' chunks, then rank exactly.
 */
public final class MemoryFileVectorIndex implements FileVectorIndex {

    /** File → embedding model → chunks. */
    private final Map<FileId, Map<String, List<VectorChunk>>> files = new ConcurrentHashMap<>();

    @Override
    public void replaceFile(FileId file, String embeddingModel, List<VectorChunk> chunks) {
        FileIndexChecks.check(file, embeddingModel, chunks);
        if (chunks.isEmpty()) {
            files.computeIfPresent(file, (id, models) -> {
                models.remove(embeddingModel);
                return models.isEmpty() ? null : models;
            });
            return;
        }
        files.computeIfAbsent(file, id -> new ConcurrentHashMap<>()).put(embeddingModel, List.copyOf(chunks));
    }

    @Override
    public void deleteFile(FileId file) {
        files.remove(file);
    }

    @Override
    public long chunkCount(FileId file, String embeddingModel) {
        return files.getOrDefault(file, Map.of()).getOrDefault(embeddingModel, List.of()).size();
    }

    @Override
    public List<FileHit> search(Set<FileId> searchIn, String embeddingModel, float[] queryEmbedding, int topK,
                                Map<String, String> metadata) {
        FileIndexChecks.requireModel(embeddingModel);
        if (topK <= 0) {
            return List.of();
        }
        Map<String, String> required = metadata == null ? Map.of() : metadata;
        double queryNorm = norm(queryEmbedding);
        List<FileHit> hits = new ArrayList<>();
        for (FileId file : searchIn) {
            for (VectorChunk chunk : files.getOrDefault(file, Map.of()).getOrDefault(embeddingModel, List.of())) {
                if (chunk.embedding().length != queryEmbedding.length
                        || !chunk.metadata().entrySet().containsAll(required.entrySet())) {
                    continue;
                }
                hits.add(new FileHit(file, withoutEmbedding(chunk),
                        cosine(queryEmbedding, queryNorm, chunk.embedding())));
            }
        }
        hits.sort(Comparator.comparingDouble(FileHit::score).reversed());
        return hits.size() > topK ? List.copyOf(hits.subList(0, topK)) : hits;
    }

    private static VectorChunk withoutEmbedding(VectorChunk chunk) {
        return new VectorChunk(chunk.id(), chunk.fileId(), chunk.ordinal(), chunk.text(),
                chunk.metadata(), new float[0]);
    }

    private static double cosine(float[] query, double queryNorm, float[] embedding) {
        double dot = 0;
        for (int i = 0; i < query.length; i++) {
            dot += query[i] * embedding[i];
        }
        double norms = queryNorm * norm(embedding);
        return norms == 0 ? 0 : dot / norms;
    }

    private static double norm(float[] vector) {
        double sum = 0;
        for (float v : vector) {
            sum += v * v;
        }
        return Math.sqrt(sum);
    }
}
