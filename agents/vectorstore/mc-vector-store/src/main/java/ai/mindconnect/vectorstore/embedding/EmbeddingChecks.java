package ai.mindconnect.vectorstore.embedding;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** What every {@link EmbeddingIndex} checks before it writes or searches. */
public final class EmbeddingChecks {

    private EmbeddingChecks() {
    }

    /**
     * Rejects a missing ref or version, a blank model, duplicate chunk ids,
     * chunks without text, mixed or zero dimensions, and vectors that are
     * all zero or not finite with
     * {@link IllegalArgumentException}.
     */
    public static void checkReplace(EntityRef ref, String version, String embeddingModel, List<EmbeddingChunk> chunks) {
        if (ref == null) {
            throw new IllegalArgumentException("An entity ref is required");
        }
        if (version == null) {
            throw new IllegalArgumentException("A version is required — a constant for entities that never change");
        }
        requireModel(embeddingModel);
        Set<String> ids = new HashSet<>();
        int dimension = chunks.isEmpty() ? 0 : chunks.get(0).embedding().length;
        for (EmbeddingChunk chunk : chunks) {
            if (chunk.id() == null || !ids.add(chunk.id())) {
                throw new IllegalArgumentException("Chunk id '" + chunk.id() + "' is missing or occurs twice in " + ref);
            }
            if (chunk.text() == null) {
                throw new IllegalArgumentException("Chunk '" + chunk.id() + "' of " + ref + " has no text");
            }
            if (chunk.embedding().length != dimension) {
                throw new IllegalArgumentException("Chunk '" + chunk.id() + "' has dimension "
                        + chunk.embedding().length + ", the first chunk of " + ref + " " + dimension);
            }
            requireUsable(chunk.embedding(), "Chunk '" + chunk.id() + "' of " + ref);
        }
    }

    public static void checkRelocate(EntityRef from, EntityRef to) {
        if (!from.type().equals(to.type()) || !from.source().equals(to.source())) {
            throw new IllegalArgumentException("An entity moves within its type and source: " + from + " → " + to);
        }
    }

    public static void requireModel(String embeddingModel) {
        if (embeddingModel == null || embeddingModel.isBlank()) {
            throw new IllegalArgumentException("An embedding model is required");
        }
    }

    /**
     * A vector without direction has no cosine similarity to anything, and one
     * with NaN or infinite components none that means anything — pgvector
     * rejects those outright.
     */
    public static void requireUsable(float[] vector, String what) {
        if (vector.length == 0) {
            throw new IllegalArgumentException(what + " has no embedding");
        }
        boolean direction = false;
        for (float v : vector) {
            if (!Float.isFinite(v)) {
                throw new IllegalArgumentException(what + " has a component that is not a finite number");
            }
            direction |= v != 0f;
        }
        if (!direction) {
            throw new IllegalArgumentException(what + " is an all-zero vector");
        }
    }
}
