package ai.mindconnect.adminui.config;

import ai.mindconnect.agent.NamespacePurge;
import ai.mindconnect.vectorstore.tools.DefaultVectorStores;
import ai.mindconnect.vectorstore.tools.VectorStores;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The vector stores are the runtime's; what the admin UI adds is clearing a
 * deleted namespace from indexes in databases of their own and forgetting it.
 */
@Configuration
public class VectorStoreConfig {

    /**
     * A deleted namespace: its rows in indexes of a database of their own are
     * removed, its registry and indexes forgotten. The application's database
     * and the namespace's directory are cleared by the namespace purge itself.
     */
    @Bean
    NamespacePurge vectorStorePurge(VectorStores stores) {
        return namespace -> {
            if (stores instanceof DefaultVectorStores defaults) defaults.purge(namespace);
        };
    }
}
