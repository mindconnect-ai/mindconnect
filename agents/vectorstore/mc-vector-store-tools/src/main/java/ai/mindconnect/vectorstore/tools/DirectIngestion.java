package ai.mindconnect.vectorstore.tools;

import ai.mindconnect.vectorstore.VectorChunk;
import ai.mindconnect.vectorstore.VectorStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Workflow-free ingestion: plain text in, embedded chunks in the store out —
 * {@link DefaultChunker} (OpenAI-style 800/400) instead of a pipeline. The
 * default whenever a store's template names no ingestion workflow; templates
 * that do name one keep the fully customisable workflow path.
 */
public final class DirectIngestion {

    private DirectIngestion() {}

    /**
     * Replaces {@code fileId}'s chunks in {@code store} with the freshly
     * chunked and embedded {@code text}. Returns a human-readable summary.
     */
    public static String ingest(VectorStores stores, VectorStore store, String storeName,
                                String fileId, String text) {
        List<String> pieces = DefaultChunker.chunk(text);
        if (pieces.isEmpty()) {
            return fileId + ": no text content to ingest.";
        }
        List<float[]> vectors = stores.embedFor(storeName, pieces);
        List<VectorChunk> chunks = new ArrayList<>(pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            chunks.add(new VectorChunk(fileId + ":" + i, fileId, i, pieces.get(i),
                    Map.of("file", fileId), vectors.get(i)));
        }
        store.deleteFile(fileId);   // replace semantics, like vector_upsert
        store.upsert(chunks);
        return "Stored " + chunks.size() + " chunk(s) for file '" + fileId + "' in store '"
                + storeName + "' (dimension " + vectors.get(0).length + ").";
    }
}
