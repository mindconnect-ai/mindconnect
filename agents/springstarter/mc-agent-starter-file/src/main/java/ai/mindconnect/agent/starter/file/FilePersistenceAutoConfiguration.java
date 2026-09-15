package ai.mindconnect.agent.starter.file;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.NamespaceRouted;
import ai.mindconnect.agent.Scope;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.ThreadBoundScope;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.filestore.FileStore;
import ai.mindconnect.filestore.FileStoreBackend;
import ai.mindconnect.llm.adapter.file.EncryptingLlmConfigRepository;
import ai.mindconnect.llm.adapter.file.FileLlmConfigRepository;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.user.adapter.file.FileApiTokenRepository;
import ai.mindconnect.user.adapter.file.FileUserRepository;
import ai.mindconnect.user.port.out.ApiTokenRepository;
import ai.mindconnect.user.port.out.UserRepository;
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
 * <p>Every store is routed per namespace: the bean is a {@link NamespaceRouted}
 * proxy, the adapter behind it is built for the namespace the
 * {@link ScopeSupplier} names when a call comes in.
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
     * Where this server works: a {@link Scope} bound to the thread per
     * request or per queued task. Until every entry point binds one, an
     * unbound thread works in {@code mindconnect.namespace} (default
     * {@code local}); that fallback goes once the request filter and the
     * start-up routines bind, and an unbound thread becomes an error.
     */
    @Bean
    @ConditionalOnMissingBean(ScopeSupplier.class)
    ThreadBoundScope scopeSupplier(@Value("${mindconnect.namespace:local}") String fallbackNamespace) {
        return ThreadBoundScope.withFallback(Scope.of(new Namespace(fallbackNamespace)));
    }

    /**
     * Encrypted at rest when the application has an {@link EncryptionHelper};
     * plain otherwise — which the CLI and embedders without a key accept.
     */
    @Bean
    @ConditionalOnMissingBean(LlmConfigRepository.class)
    LlmConfigRepository llmConfigRepository(@Value("${mindconnect.data.base-dir:data}") String baseDir,
                                            ScopeSupplier scope,
                                            ObjectProvider<EncryptionHelper> encryption) {
        LlmConfigRepository files = NamespaceRouted.route(LlmConfigRepository.class, scope,
                ns -> new FileLlmConfigRepository(Path.of(baseDir), ns));
        EncryptionHelper helper = encryption.getIfAvailable();
        if (helper == null) {
            log.warn("No EncryptionHelper — LLM credentials are stored unencrypted under {}", baseDir);
            return files;
        }
        return new EncryptingLlmConfigRepository(files, helper);
    }

    /** The installation's users, under {@code <mindconnect.data.base-dir>/<namespace>/system/users}. */
    @Bean
    @ConditionalOnMissingBean(UserRepository.class)
    UserRepository userRepository(@Value("${mindconnect.data.base-dir:data}") String baseDir, ScopeSupplier scope) {
        return NamespaceRouted.route(UserRepository.class, scope, ns -> new FileUserRepository(Path.of(baseDir), ns));
    }

    /** Personal API tokens, stored as hashes under {@code <mindconnect.data.base-dir>/<namespace>/system/api-tokens}. */
    @Bean
    @ConditionalOnMissingBean(ApiTokenRepository.class)
    ApiTokenRepository apiTokenRepository(@Value("${mindconnect.data.base-dir:data}") String baseDir, ScopeSupplier scope) {
        return NamespaceRouted.route(ApiTokenRepository.class, scope, ns -> new FileApiTokenRepository(Path.of(baseDir), ns));
    }

    /** Uploads: the {@code filesystem} backend under {@code <mindconnect.data.base-dir>/<namespace>/files} unless configured otherwise. */
    @Bean
    @ConditionalOnMissingBean(FileStore.class)
    FileStore fileStore(@Value("${mindconnect.file-store.backend:filesystem}") String backend,
                        @Value("${mindconnect.data.base-dir:data}") String baseDir,
                        ScopeSupplier scope) {
        FileStoreBackend files = FileStoreBackend.byType(backend)
                .orElseThrow(() -> new IllegalStateException("No file-store backend '" + backend
                        + "' on the classpath (available: "
                        + FileStoreBackend.discover().stream().map(FileStoreBackend::type).toList() + ")"));
        return NamespaceRouted.route(FileStore.class, scope,
                ns -> files.open(Map.of("baseDir", baseDir, "namespace", ns.value())));
    }
}
