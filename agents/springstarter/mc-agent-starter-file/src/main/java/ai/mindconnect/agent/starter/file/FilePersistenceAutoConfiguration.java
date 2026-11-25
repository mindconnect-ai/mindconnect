package ai.mindconnect.agent.starter.file;

import ai.mindconnect.agent.sdk.AgentRuntime;
import ai.mindconnect.agent.tool.TimeZones;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.user.domain.User;
import ai.mindconnect.user.port.out.PreferenceRepository;
import ai.mindconnect.user.runtime.FileUserPlugin;
import ai.mindconnect.user.runtime.UserPlugin;
import ai.mindconnect.user.service.UserService;
import ai.mindconnect.credentials.adapter.env.EncryptingConnectionRepository;
import ai.mindconnect.credentials.adapter.env.EncryptingOAuthProviderRepository;
import ai.mindconnect.credentials.adapter.file.FileConnectionRepository;
import ai.mindconnect.credentials.adapter.file.FileOAuthProviderRepository;
import ai.mindconnect.credentials.port.out.OAuthProviderRepository;
import ai.mindconnect.credentials.port.out.ConnectionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import ai.mindconnect.user.port.out.UserToolRepository;
import ai.mindconnect.user.port.out.ApiTokenRepository;
import ai.mindconnect.user.port.out.NotificationRepository;
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
 * {@code mindconnect.persistence}; this starter adds what is
 * installation-wide — the users and their API tokens, as a plugin of the
 * runtime, and the credentials users attached.
 *
 * <p>It declares no scope: an application works in one namespace unless it also
 * has {@code mc-agent-starter-namespace}, which binds a scope per request and
 * owns the {@code ThreadBoundScopeSupplier} bean. A host without it — the CLI, an
 * embedded server — gets a runtime fixed to {@code mindconnect.namespace},
 * which is what a single-namespace application wants and what its own start-up
 * threads can work with.
 * The default: {@code mindconnect.persistence=file}, or nothing at all.
 */
@AutoConfiguration
@ConditionalOnProperty(name = "mindconnect.persistence", havingValue = "file", matchIfMissing = true)
public class FilePersistenceAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(FilePersistenceAutoConfiguration.class);

    /**
     * The users, their API tokens, tools, notices and preferences, installed into the
     * runtime: the runtime asks a user's zone and own variables, so it holds the stores.
     * Installation-wide, not per namespace: a user is the same person in every namespace.
     */
    @Bean
    @ConditionalOnMissingBean(UserPlugin.class)
    FileUserPlugin fileUserPlugin(@Value("${mindconnect.data.base-dir:data}") String baseDir,
                                  ObjectProvider<EncryptionHelper> encryption,
                                  @Value("${mindconnect.time-zone:}") String installationZone) {
        return new FileUserPlugin(Path.of(baseDir), encryption.getIfAvailable(), installationZone);
    }

    // ── the runtime's users, for the application's own beans ────────────────

    @Bean UserRepository userRepository(AgentRuntime r) { return r.service(UserRepository.class); }
    @Bean ApiTokenRepository apiTokenRepository(AgentRuntime r) { return r.service(ApiTokenRepository.class); }
    @Bean UserToolRepository userToolRepository(AgentRuntime r) { return r.service(UserToolRepository.class); }
    @Bean NotificationRepository notificationRepository(AgentRuntime r) { return r.service(NotificationRepository.class); }
    @Bean PreferenceRepository preferenceRepository(AgentRuntime r) { return r.service(PreferenceRepository.class); }
    @Bean UserService userService(AgentRuntime r) { return r.service(UserService.class); }
    /** The zone each user lives in, for the profile page as for the runtime's prompt and tools. */
    @Bean TimeZones timeZones(AgentRuntime r) { return r.service(TimeZones.class); }

    /** Says what the start-up rewrite of earlier ciphertexts changed — nothing on every start after the first. */
    private static void logRewritten(int records, String what) {
        if (records > 0) log.info("{} {} rewritten from the earlier encryption scheme to AES-GCM", records, what);
    }

    /**
     * The OAuth apps this installation can connect through. Installation-wide,
     * and the client secret encrypted at rest — a public client, which is what
     * a shared app registration is, has none to encrypt.
     */
    @Bean
    @ConditionalOnMissingBean(OAuthProviderRepository.class)
    OAuthProviderRepository oAuthProviderRepository(@Value("${mindconnect.data.base-dir:data}") String baseDir,
                                                    ObjectMapper objectMapper,
                                                    ObjectProvider<EncryptionHelper> encryption) {
        OAuthProviderRepository files = new FileOAuthProviderRepository(Path.of(baseDir), objectMapper);
        EncryptionHelper helper = encryption.getIfAvailable();
        if (helper == null) return files;
        EncryptingOAuthProviderRepository encrypting = new EncryptingOAuthProviderRepository(files, helper);
        logRewritten(encrypting.rewriteEarlierScheme(), "OAuth provider(s)");
        return encrypting;
    }

    /**
     * The accounts users attached. Installation-wide like the users
     * themselves, and encrypted like their variables — a mailbox password is
     * a secret in the same sense an API key is.
     */
    @Bean
    @ConditionalOnMissingBean(ConnectionRepository.class)
    ConnectionRepository connectionRepository(@Value("${mindconnect.data.base-dir:data}") String baseDir,
                                              ObjectMapper objectMapper,
                                              ObjectProvider<EncryptionHelper> encryption,
                                              UserRepository users) {
        ConnectionRepository files = new FileConnectionRepository(Path.of(baseDir), objectMapper);
        EncryptionHelper helper = encryption.getIfAvailable();
        if (helper == null) {
            log.warn("No EncryptionHelper — connection credentials are stored unencrypted under {}", baseDir);
            return files;
        }
        EncryptingConnectionRepository encrypting = new EncryptingConnectionRepository(files, helper);
        logRewritten(encrypting.rewriteEarlierScheme(users.findAll().stream().map(User::id).toList()), "connection(s)");
        return encrypting;
    }
}
