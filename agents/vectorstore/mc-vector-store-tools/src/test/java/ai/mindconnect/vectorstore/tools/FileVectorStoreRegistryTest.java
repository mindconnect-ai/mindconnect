package ai.mindconnect.vectorstore.tools;

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
                VectorStoreInstance candidate = new VectorStoreInstance("session-s1", "chat-uploads", "memory",
                        Map.of(), "embeddings", null, Map.of("attempt", Integer.toString(i)),
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
        VectorStoreTemplate template = new VectorStoreTemplate("Chat Uploads", "memory", Map.of(),
                "embeddings", null, Map.of("description", "per chat"));

        registry.saveTemplate(template);
        VectorStoreInstance instance = registry.registerInstance(
                VectorStoreInstance.fromTemplate("docs", template, VectorStoreInstance.Scope.GLOBAL, null));

        assertThat(registry.template("Chat Uploads")).contains(template);
        assertThat(registry.templates()).containsExactly(template);
        assertThat(registry.instances(VectorStoreInstance.Scope.GLOBAL, null)).containsExactly(instance);
        registry.deleteInstance("docs");
        assertThat(registry.instance("docs")).isEmpty();
    }
}
