package ai.mindconnect.vectorstore.embedding;

import ai.mindconnect.agent.UserId;

import java.util.List;

/**
 * Where a {@link MemoryEmbeddingIndex} keeps its entries between restarts.
 * The index calls it under its own write lock, before it changes the heap, so
 * a failed write leaves the index as it was.
 */
public interface EntryStore {

    /** Keeps nothing. */
    EntryStore NONE = new EntryStore() {
        @Override public List<Entry> entries() { return List.of(); }
        @Override public void write(Entry entry) { }
        @Override public void remove(EntityRef ref, String embeddingModel) { }
        @Override public List<MetadataField> fields() { return List.of(); }
        @Override public void writeFields(List<MetadataField> fields) { }
    };

    /** Everything stored, read once when the index starts. */
    List<Entry> entries();

    /** Stores the entry, replacing what was stored for its ref and model. */
    void write(Entry entry);

    /** Removes what was stored for the ref and model; nothing stored is fine. */
    void remove(EntityRef ref, String embeddingModel);

    List<MetadataField> fields();

    void writeFields(List<MetadataField> fields);

    /** What one entity has under one model, vectors normalised. */
    record Entry(EntityRef ref, String embeddingModel, UserId owner, String version, List<EmbeddingChunk> chunks) {
        public Entry {
            chunks = List.copyOf(chunks);
        }
    }
}
