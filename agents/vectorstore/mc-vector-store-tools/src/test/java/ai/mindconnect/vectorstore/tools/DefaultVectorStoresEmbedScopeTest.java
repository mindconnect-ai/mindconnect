package ai.mindconnect.vectorstore.tools;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.Scope;
import ai.mindconnect.agent.ThreadBoundScope;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmConfigId;
import ai.mindconnect.llm.port.in.LlmEmbeddings;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.vectorstore.VectorStoreBackend;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code embedFor(namespace, …)} takes the embedding config from the namespace
 * it was asked about — not from whatever namespace the calling thread happens
 * to be bound to. A store of one namespace must never be filled with vectors
 * of another namespace's model.
 */
class DefaultVectorStoresEmbedScopeTest {

    @TempDir
    Path dir;

    @Test
    void theConfigIsLookedUpInTheNamespaceAskedFor_notTheThreadsOwn() throws Exception {
        ThreadBoundScope bound = ThreadBoundScope.strict();
        AtomicReference<Namespace> lookedUpIn = new AtomicReference<>();
        LlmConfigRepository configs = new LlmConfigRepository() {
            @Override public Optional<LlmConfig> findById(LlmConfigId id) { return Optional.empty(); }
            @Override public Optional<LlmConfig> findByName(String name) {
                lookedUpIn.set(bound.get().namespace());
                return Optional.of(LlmConfig.lmStudio(name, "fake-model", "http://unused"));
            }
            @Override public List<LlmConfig> findAll() { return List.of(); }
            @Override public void save(LlmConfig config) { }
            @Override public void deleteById(LlmConfigId id) { }
        };
        LlmEmbeddings embeddings = (config, texts) -> texts.stream().map(t -> new float[]{1f}).toList();
        VectorStoreTemplate template = new VectorStoreTemplate("default", "memory", Map.of(), "embeddings",
                "file-ingestion", Map.of());
        DefaultVectorStores stores = new DefaultVectorStores(VectorStoreBackend.discover(), template, dir,
                embeddings, configs, bound);

        List<float[]> vectors = bound.runIn(Scope.of(new Namespace("local")),
                () -> stores.embedFor(new Namespace("acme"), "docs", List.of("hello")));

        assertThat(vectors).hasSize(1);
        assertThat(lookedUpIn.get()).isEqualTo(new Namespace("acme"));
        assertThat(bound.isBound()).as("the thread is unbound again, as it was before").isFalse();
    }
}
