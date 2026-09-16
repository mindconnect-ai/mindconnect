package ai.mindconnect.agent.starter.file;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.Scope;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.ThreadBoundScope;
import ai.mindconnect.agent.runtime.feature.Persistence;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.user.adapter.env.EncryptingUserRepository;
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

import java.nio.file.Path;

/**
 * File persistence for the agent runtime: everything under
 * {@code mindconnect.data.base-dir} (default {@code data}), one directory
 * per namespace. The runtime's stores are built by the runtime starter from
 * the {@link Persistence} declared here; this starter adds what is
 * installation-wide and not the runtime's — the users and their API tokens.
 * The default: {@code mindconnect.persistence=file}, or nothing at all.
 */
@AutoConfiguration
@ConditionalOnProperty(name = "mindconnect.persistence", havingValue = "file", matchIfMissing = true)
public class FilePersistenceAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(FilePersistenceAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(Persistence.class)
    Persistence persistence(@Value("${mindconnect.data.base-dir:data}") String baseDir) {
        return Persistence.file(Path.of(baseDir));
    }

    /**
     * Where this server works: a scope bound per request by the namespace
     * starter's filter and per task by the runtime; a thread neither binds
     * works in {@code mindconnect.namespace} (default {@code local}).
     */
    @Bean
    @ConditionalOnMissingBean(ScopeSupplier.class)
    ThreadBoundScope scopeSupplier(@Value("${mindconnect.namespace:local}") String fallbackNamespace) {
        return ThreadBoundScope.withFallback(Scope.of(new Namespace(fallbackNamespace)));
    }

    /** Installation-wide, not per namespace: a user is the same person in every namespace. */
    @Bean
    @ConditionalOnMissingBean(UserRepository.class)
    UserRepository userRepository(@Value("${mindconnect.data.base-dir:data}") String baseDir,
                                  ObjectProvider<EncryptionHelper> encryption) {
        UserRepository files = new FileUserRepository(Path.of(baseDir));
        EncryptionHelper helper = encryption.getIfAvailable();
        // A user's own variables are secrets — enc: at rest when there is a key, like LLM credentials.
        if (helper == null) {
            log.warn("No EncryptionHelper — users' own variables are stored unencrypted under {}", baseDir);
            return files;
        }
        return new EncryptingUserRepository(files, helper);
    }

    @Bean
    @ConditionalOnMissingBean(ApiTokenRepository.class)
    ApiTokenRepository apiTokenRepository(@Value("${mindconnect.data.base-dir:data}") String baseDir) {
        return new FileApiTokenRepository(Path.of(baseDir));
    }
}
