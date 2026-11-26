package ai.mindconnect.vectorstore.tools;

import ai.mindconnect.agent.scope.Namespace;
import ai.mindconnect.agent.scope.Scope;
import ai.mindconnect.llm.management.api.domain.LlmConfig;
import ai.mindconnect.llm.management.api.domain.LlmConfigId;
import ai.mindconnect.llm.runtime.api.port.in.LlmEmbedding;
import ai.mindconnect.llm.management.api.port.out.LlmConfigRepository;
import ai.mindconnect.namespace.service.ThreadBoundScopeSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import ai.mindconnect.jackson.JsonMapperFactory;
import ai.mindconnect.agent.jackson.IdJsonModule;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code embedFor} takes the embedding config from the namespace the call works
 * in. A store of one namespace must never be filled with vectors of another
 * namespace's model.
 */
class DefaultVectorStoresEmbedScopeTest {

    @TempDir
    Path dir;

    @Test
    void theConfigIsLookedUpInTheNamespaceOfTheCall() throws Exception {
        ThreadBoundScopeSupplier bound = ThreadBoundScopeSupplier.strict();
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
        LlmEmbedding embeddings = (config, texts) -> texts.stream().map(t -> new float[]{1f}).toList();
        VectorStoreTemplate template = new VectorStoreTemplate("default", "embeddings", "file-ingestion", Map.of());
        DefaultVectorStores stores = new DefaultVectorStores(template, dir, embeddings, configs, bound, new JsonMapperFactory(List.of(new IdJsonModule())));

        List<float[]> vectors = bound.runIn(Scope.of(new Namespace("acme")),
                () -> stores.embedFor("docs", List.of("hello")));

        assertThat(vectors).hasSize(1);
        assertThat(lookedUpIn.get()).isEqualTo(new Namespace("acme"));
        assertThat(bound.isBound()).as("the thread is unbound again, as it was before").isFalse();
    }
}
