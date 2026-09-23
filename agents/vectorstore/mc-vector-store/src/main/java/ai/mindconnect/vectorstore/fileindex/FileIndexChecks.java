package ai.mindconnect.vectorstore.fileindex;

import ai.mindconnect.filestore.FileId;
import ai.mindconnect.vectorstore.VectorChunk;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** What every {@link FileVectorIndex} checks before writing a file's chunks. */
public final class FileIndexChecks {

    private FileIndexChecks() {
    }

    /**
     * Rejects a blank model, chunks of another file, duplicate chunk ids and
     * mixed dimensions with {@link IllegalArgumentException}.
     */
    public static void check(FileId file, String embeddingModel, List<VectorChunk> chunks) {
        if (file == null) {
            throw new IllegalArgumentException("A file is required");
        }
        requireModel(embeddingModel);
        Set<String> ids = new HashSet<>();
        int dimension = chunks.isEmpty() ? 0 : chunks.get(0).embedding().length;
        for (VectorChunk chunk : chunks) {
            if (!file.value().equals(chunk.fileId())) {
                throw new IllegalArgumentException("Chunk '" + chunk.id() + "' belongs to file '"
                        + chunk.fileId() + "', not '" + file + "'");
            }
            if (!ids.add(chunk.id())) {
                throw new IllegalArgumentException("Chunk id '" + chunk.id() + "' occurs twice in file '" + file + "'");
            }
            if (chunk.embedding().length != dimension) {
                throw new IllegalArgumentException("Chunk '" + chunk.id() + "' has dimension "
                        + chunk.embedding().length + ", the file's first chunk " + dimension);
            }
        }
        if (dimension == 0 && !chunks.isEmpty()) {
            throw new IllegalArgumentException("Chunks of file '" + file + "' have no embedding");
        }
    }

    public static void requireModel(String embeddingModel) {
        if (embeddingModel == null || embeddingModel.isBlank()) {
            throw new IllegalArgumentException("An embedding model is required");
        }
    }
}
