package ai.mindconnect.adminui.config;

import ai.mindconnect.agent.tool.ToolEnvironment;
import ai.mindconnect.llm.port.in.LlmEmbeddings;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.vectorstore.tools.VectorStores;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * One {@link VectorStores} for the whole app — the same resolution the
 * knowledge tools use, shared by the vector-store admin UI and the chat
 * session file endpoints.
 */
@Configuration
public class VectorStoreConfig {

    @Bean
    VectorStores vectorStores(LlmEmbeddings embeddings, LlmConfigRepository llmConfigs,
                              @Value("${mindconnect.vector-store.backend:memory}") String backend,
                              @Value("${mindconnect.vector-store.dir:data/vector-stores}") String dir,
                              @Value("${mindconnect.vector-store.url:}") String url,
                              @Value("${mindconnect.vector-store.user:}") String user,
                              @Value("${mindconnect.vector-store.password:}") String password,
                              @Value("${mindconnect.vector-store.embedding-config:embeddings}") String embeddingConfig) {
        Map<String, String> strings = new LinkedHashMap<>();
        strings.put("vectorStoreBackend", backend);
        strings.put("vectorStoreDir", dir);
        strings.put("vectorStoreUrl", url);
        strings.put("vectorStoreUser", user);
        strings.put("vectorStorePassword", password);
        strings.put("vectorStoreEmbeddingConfig", embeddingConfig);
        return VectorStores.fromEnvironment(new ToolEnvironment() {
            @Override @SuppressWarnings("unchecked")
            public <T> Optional<T> get(Class<T> type) {
                if (type == LlmEmbeddings.class) return Optional.of((T) embeddings);
                if (type == LlmConfigRepository.class) return Optional.of((T) llmConfigs);
                return Optional.empty();
            }
            @Override public Optional<String> getString(String key) {
                return Optional.ofNullable(strings.get(key)).filter(s -> !s.isBlank());
            }
        }).orElseThrow(() -> new IllegalStateException("Vector store setup incomplete"));
    }
}
