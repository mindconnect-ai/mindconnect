package ai.mindconnect.vectorstore.tools;

import ai.mindconnect.vectorstore.embedding.EntityRef;
import ai.mindconnect.vectorstore.embedding.EntityType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two uploads into one chat open the same session store at the same moment,
 * each through its own registry. The store is registered once, and both get
 * that one record back.
 */
class FileVectorStoreRegistryTest {

    @Test
    void concurrentRegistrationsOfOneStoreAgreeOnOneRecord(@TempDir Path dir) throws Exception {
        List<Future<VectorStoreInstance>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 50; i++) {
                FileVectorStoreRegistry registry = new FileVectorStoreRegistry(dir.resolve("vector-stores"));
                VectorStoreInstance candidate = new VectorStoreInstance("session-s1", "chat-uploads",
                        "embeddings", null, Map.of("attempt", Integer.toString(i)),
                        VectorStoreInstance.Scope.SESSION, "s1", "alice", Instant.now());
                futures.add(pool.submit(() -> registry.registerInstance(candidate)));
            }
        }

        VectorStoreInstance first = futures.get(0).get();
        for (Future<VectorStoreInstance> future : futures) {
            assertThat(future.get()).isEqualTo(first);
        }
        FileVectorStoreRegistry registry = new FileVectorStoreRegistry(dir.resolve("vector-stores"));
        assertThat(registry.instance("session-s1")).contains(first);
        try (var files = Files.list(dir.resolve("vector-stores/instances"))) {
            assertThat(files).hasSize(1);
        }
    }

    @Test
    void templatesAndInstancesRoundTrip(@TempDir Path dir) {
        FileVectorStoreRegistry registry = new FileVectorStoreRegistry(dir.resolve("vector-stores"));
        VectorStoreTemplate template = new VectorStoreTemplate("Chat Uploads", "embeddings", null, Map.of("description", "per chat"));

        VectorStoreTemplate saved = registry.saveTemplate(template);
        VectorStoreInstance instance = registry.registerInstance(
                VectorStoreInstance.fromTemplate("docs", template, VectorStoreInstance.Scope.GLOBAL, null));

        assertThat(saved.version()).isEqualTo(1L);
        assertThat(registry.template("Chat Uploads")).contains(saved);
        assertThat(registry.templates()).containsExactly(saved);
        assertThat(registry.instances(VectorStoreInstance.Scope.GLOBAL, null)).containsExactly(instance);
        registry.deleteInstance("docs");
        assertThat(registry.instance("docs")).isEmpty();
    }

    @Test
    void membersAreListedRemovedAndGoWithTheStore(@TempDir Path dir) {
        VectorStoreRegistry registry = new FileVectorStoreRegistry(dir.resolve("vector-stores"));
        EntityRef file = EntityRef.of(EntityType.FILE, EntityRef.FILE_STORE, "file-1");
        EntityRef doc = EntityRef.of(EntityType.DOCUMENT, "kb", "notes.md");
        registry.registerInstance(VectorStoreInstance.fromTemplate("kb",
                new VectorStoreTemplate("default", "embeddings", null, Map.of()), VectorStoreInstance.Scope.GLOBAL, null));

        registry.addMember("kb", file);
        registry.addMember("kb", doc);
        registry.addMember("kb", file);
        registry.addMember("session-s1", file);

        assertThat(registry.members("kb")).containsExactly(file, doc);
        assertThat(registry.members("KB")).as("found by key, like the instance").containsExactly(file, doc);
        assertThat(registry.storesListing(file)).containsExactlyInAnyOrder("kb", "session-s1");

        registry.removeMember("kb", doc);
        registry.removeMember("kb", doc);
        assertThat(registry.members("kb")).containsExactly(file);

        registry.deleteInstance("kb");
        assertThat(registry.members("kb")).isEmpty();
        assertThat(registry.storesListing(file)).containsExactly("session-s1");
    }

    @Test
    void indexDefinitionsRoundTrip(@TempDir Path dir) {
        VectorStoreRegistry registry = new FileVectorStoreRegistry(dir.resolve("vector-stores"));
        IndexDefinition big = new IndexDefinition("big-kb", "pgvector", "mc_embedding_big", null, null, null, null, "large");

        registry.saveIndex(big);
        registry.saveIndex(big);

        assertThat(registry.indexes()).containsExactly(big);
        assertThat(registry.index("big-kb")).contains(big);
        registry.deleteIndex("big-kb");
        assertThat(registry.indexes()).isEmpty();
    }
}
