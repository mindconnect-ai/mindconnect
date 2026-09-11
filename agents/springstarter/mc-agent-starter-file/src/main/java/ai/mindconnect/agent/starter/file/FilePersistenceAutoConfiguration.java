package ai.mindconnect.agent.starter.file;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.filestore.FileStore;
import ai.mindconnect.filestore.FileStoreBackend;
import ai.mindconnect.llm.adapter.file.EncryptingLlmConfigRepository;
import ai.mindconnect.llm.adapter.file.FileLlmConfigRepository;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.nio.file.Path;
import java.util.Map;

/**
 * File persistence — the default. Every repository port of the agent
 * runtime, the message store, the LLM gateway and the file store is served
 * by its file adapter under {@code mindconnect.data.base-dir}. Active unless
 * {@code mindconnect.persistence} says otherwise; then the matching starter
 * (Postgres, say) takes over and this one stays silent.
 *
 * <p>Auto-configured: having {@code mc-agent-starter-file} on the classpath
 * is enough. Every bean here backs off when the application defines its
 * own of the same type.
 */
@AutoConfiguration
@ConditionalOnProperty(name = "mindconnect.persistence", havingValue = "file", matchIfMissing = true)
@Import({FileRepositoriesConfig.class, FileMessageRepositoryConfig.class})
public class FilePersistenceAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(FilePersistenceAutoConfiguration.class);

    /**
     * The one namespace this JVM runs in ({@code mindconnect.namespace}, default
     * {@code local}). Every repository is bound to it when it is built; nothing
     * above the repositories names it.
     */
    @Bean
    Namespace mindconnectNamespace(@Value("${mindconnect.namespace:local}") String namespace) {
        return new Namespace(namespace);
    }

    /**
     * Encrypted at rest when the application has an {@link EncryptionHelper};
     * plain otherwise — which the CLI and embedders without a key accept.
     */
    @Bean
    @ConditionalOnMissingBean(LlmConfigRepository.class)
    LlmConfigRepository llmConfigRepository(@Value("${mindconnect.data.base-dir:data}") String baseDir,
                                            Namespace namespace,
                                            ObjectProvider<EncryptionHelper> encryption) {
        LlmConfigRepository files = new FileLlmConfigRepository(Path.of(baseDir), namespace);
        EncryptionHelper helper = encryption.getIfAvailable();
        if (helper == null) {
            log.warn("No EncryptionHelper — LLM credentials are stored unencrypted under {}", baseDir);
            return files;
        }
        return new EncryptingLlmConfigRepository(files, helper);
    }

    /** Uploads: the {@code filesystem} backend under {@code <mindconnect.data.base-dir>/<namespace>/files} unless configured otherwise. */
    @Bean
    @ConditionalOnMissingBean(FileStore.class)
    FileStore fileStore(@Value("${mindconnect.file-store.backend:filesystem}") String backend,
                        @Value("${mindconnect.data.base-dir:data}") String baseDir,
                        Namespace namespace) {
        return FileStoreBackend.byType(backend)
                .orElseThrow(() -> new IllegalStateException("No file-store backend '" + backend
                        + "' on the classpath (available: "
                        + FileStoreBackend.discover().stream().map(FileStoreBackend::type).toList() + ")"))
                .open(Map.of("baseDir", baseDir, "namespace", namespace.value()));
    }
}
