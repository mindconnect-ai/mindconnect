package ai.mindconnect.agentrest.service;

import ai.mindconnect.common.Page;
import ai.mindconnect.filestore.FileId;
import ai.mindconnect.filestore.FileStore;
import ai.mindconnect.filestore.StoredFile;
import ai.mindconnect.vectorstore.embedding.EmbeddingIndex.IndexedEntity;
import ai.mindconnect.vectorstore.embedding.EntityRef;
import ai.mindconnect.vectorstore.embedding.EntityType;
import ai.mindconnect.vectorstore.tools.EntitySelector;
import ai.mindconnect.vectorstore.tools.VectorStore;
import ai.mindconnect.vectorstore.tools.VectorStoreInstance;
import ai.mindconnect.vectorstore.tools.VectorStores;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What the vector stores hold, for someone who looks through them: how much
 * each store has, its entries by name and its chunks, searched and a page at
 * a time. Read-only; changing a store is the {@link VectorStoreService}'s.
 */
@Service
public class VectorStoreContents {

    private final ObjectProvider<VectorStores> stores;
    private final ObjectProvider<FileStore> files;

    public VectorStoreContents(ObjectProvider<VectorStores> stores, ObjectProvider<FileStore> files) {
        this.stores = stores;
        this.files = files;
    }

    /** A store as an overview shows it; a size is empty when the store cannot say. */
    public record StoreSummary(VectorStoreInstance instance, String index, OptionalLong entries, OptionalLong chunks) {}

    /** The stores {@code shown} lets through, each with its index and how much it holds. */
    public List<StoreSummary> summaries(Predicate<VectorStoreInstance> shown) {
        VectorStores vs = stores.getObject();
        // Opening the index first brings in what the namespace kept before 0.9.
        vs.index();
        return vs.registry().instances().stream().filter(shown).map(instance -> {
            try {
                List<IndexedEntity> entities = vs.store(instance.name()).entities();
                return new StoreSummary(instance, vs.indexOf(instance), OptionalLong.of(entities.size()),
                        OptionalLong.of(entities.stream().mapToLong(IndexedEntity::chunks).sum()));
            } catch (RuntimeException e) {
                return new StoreSummary(instance, vs.indexOf(instance), OptionalLong.empty(), OptionalLong.empty());
            }
        }).toList();
    }

    /** An entry under the name a person knows it by: a stored file's name, anything else its id. */
    public record NamedEntry(VectorStoreService.Entry entry, String name) {}

    /**
     * One page of what the store lists: the entries whose name, id or type
     * contains {@code query}, all of them without one. A page past the last is the last.
     */
    public Page<NamedEntry> entries(String storeName, String query, int page, int size) {
        String needle = query == null || query.isBlank() ? null : query.toLowerCase(Locale.ROOT);
        List<NamedEntry> matching = stores.getObject().store(storeName).entities().stream()
                .map(entity -> new NamedEntry(VectorStoreService.entry(entity), nameOf(entity.ref())))
                .filter(named -> needle == null
                        || (named.name() + " " + named.entry().entityId() + " " + named.entry().entityType())
                                .toLowerCase(Locale.ROOT).contains(needle))
                .toList();
        int pages = Math.max(1, (matching.size() + size - 1) / size);
        return Page.of(matching, Math.min(Math.max(page, 1), pages), size);
    }

    /** The names of the entries a store lists, by id, in the order it lists them. */
    public Map<String, String> entryNames(String storeName) {
        Map<String, String> names = new LinkedHashMap<>();
        stores.getObject().store(storeName).members().forEach(ref -> names.put(ref.id(), nameOf(ref)));
        return names;
    }

    /** One chunk as the index has it, with the name of the entry it was cut from. */
    public record Chunk(String entityType, String entityId, String entryName, String chunkId, int ordinal,
                        String text, Map<String, String> metadata) {}

    /**
     * One page of the chunks of the store's entries — of one entry when
     * {@code entityId} names it — whose text contains {@code query}. A page
     * past the last is the last.
     */
    public Page<Chunk> chunks(String storeName, String entityId, String query, int page, int size) {
        VectorStore store = stores.getObject().store(storeName);
        Map<String, String> names = entryNames(storeName);
        Set<EntityRef> within = entityId == null || entityId.isBlank() ? null
                : store.select(List.of(EntitySelector.id(entityId)));
        int current = Math.max(page, 1);
        var found = store.chunks(within, query, (current - 1) * size, size);
        long pages = Math.max(1, (found.total() + size - 1) / size);
        if (current > pages) {
            current = (int) pages;
            found = store.chunks(within, query, (current - 1) * size, size);
        }
        return new Page<>(found.chunks().stream()
                .map(chunk -> new Chunk(chunk.ref().type().value(), chunk.ref().id(),
                        names.getOrDefault(chunk.ref().id(), chunk.ref().id()), chunk.chunk().id(),
                        chunk.chunk().ordinal(), chunk.chunk().text(), chunk.chunk().metadata()))
                .toList(), current, size, found.total());
    }

    private String nameOf(EntityRef ref) {
        FileStore fileStore = files.getIfAvailable();
        if (fileStore == null || !ref.type().equals(EntityType.FILE)) {
            return ref.id();
        }
        try {
            return fileStore.find(FileId.of(ref.id())).map(StoredFile::name).orElse(ref.id() + " (file removed)");
        } catch (RuntimeException e) {
            return ref.id();
        }
    }
}
