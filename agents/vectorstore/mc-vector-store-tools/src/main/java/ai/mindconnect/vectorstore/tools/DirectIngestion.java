package ai.mindconnect.vectorstore.tools;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.vectorstore.embedding.EntityRef;

import java.util.List;
import java.util.Map;

/**
 * Workflow-free ingestion: plain text in, embedded chunks in the index out —
 * {@link DefaultChunker} (OpenAI-style 800/400) instead of a pipeline. The
 * default whenever a store's template names no ingestion workflow; templates
 * that do name one keep the fully customisable workflow path.
 */
public final class DirectIngestion {

    private DirectIngestion() {}

    /**
     * Chunks {@code text} as {@code ref}'s content, embeds it unless the index
     * already has {@code ref} at {@code version}, and lists {@code ref} in the
     * store. Returns a human-readable summary.
     *
     * @param name what the chunks name as their file — shown with every search hit
     */
    public static String ingest(VectorStore store, EntityRef ref, UserId owner, String version,
                                String name, String text) {
        List<String> pieces = DefaultChunker.chunk(text);
        if (pieces.isEmpty()) {
            return name + ": no text content to ingest.";
        }
        long chunks = store.put(ref, owner, version, pieces.stream()
                .map(piece -> new VectorStore.TextChunk(piece, Map.of("file", name)))
                .toList());
        return "Stored " + chunks + " chunk(s) for file '" + name + "' in store '" + store.name() + "'.";
    }
}
