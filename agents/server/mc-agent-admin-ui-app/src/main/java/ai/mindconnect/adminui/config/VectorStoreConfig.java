package ai.mindconnect.adminui.config;

import ai.mindconnect.agent.NamespacePurge;
import ai.mindconnect.vectorstore.tools.DefaultVectorStores;
import ai.mindconnect.vectorstore.tools.VectorStores;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The vector stores are the runtime's; what the admin UI adds is forgetting a deleted namespace's registry. */
@Configuration
public class VectorStoreConfig {

    /** A deleted namespace's vector-store registry is forgotten; its settings and tables go with the namespace. */
    @Bean
    NamespacePurge vectorStorePurge(VectorStores stores) {
        return namespace -> {
            if (stores instanceof DefaultVectorStores defaults) defaults.forget(namespace);
        };
    }
}
