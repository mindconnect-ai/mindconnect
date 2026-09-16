package ai.mindconnect.agent.starter.namespace;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.NamespacePurge;
import ai.mindconnect.agent.Scope;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.common.env.EnvVarResolver;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.namespace.adapter.env.EncryptingNamespaceRepository;
import ai.mindconnect.namespace.adapter.env.NamespaceEnvVarResolver;
import ai.mindconnect.user.adapter.env.UserEnvVarResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.mindconnect.user.port.out.UserRepository;
import java.util.ArrayList;
import java.util.List;
import ai.mindconnect.agent.ThreadBoundScope;
import ai.mindconnect.namespace.adapter.file.FileNamespacePurge;
import ai.mindconnect.namespace.adapter.file.FileNamespaceRepository;
import ai.mindconnect.namespace.port.out.NamespaceRepository;
import ai.mindconnect.namespace.service.NamespaceService;
import ai.mindconnect.user.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.nio.file.Path;

/**
 * Namespaces for a server: where the namespace of a request comes from, who
 * may work in which, and the {@link ThreadBoundScope} the routed stores read.
 *
 * <p>Auto-configured ahead of the persistence starters so that the scope
 * supplier defined here is the one they route by. The namespace repository
 * follows {@code mindconnect.persistence} like every other store, but is
 * installation-wide: it is the store that says which namespaces exist.
 *
 * <p>Two servlet filters: {@link NamespacePathFilter} runs before Spring
 * Security and rewrites {@code /ns/{namespace}/…}; {@link ScopeBindingFilter}
 * runs right after it, with the authentication known, and binds the scope.
 */
@AutoConfiguration(
        beforeName = {
                "ai.mindconnect.agent.starter.file.FilePersistenceAutoConfiguration",
                "ai.mindconnect.agent.starter.postgres.PostgresPersistenceConfig"})
public class NamespaceAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(NamespaceAutoConfiguration.class);

    /**
     * Where this server works: a {@link Scope} bound per request by the
     * {@link ScopeBindingFilter} and per queued task by the runtime's task
     * advisor. It is <b>strict</b> — a thread that touches a store without a
     * bound scope fails instead of quietly working in the default namespace,
     * because on a server that silence is a leak between namespaces. Start-up
     * routines therefore bind {@code mindconnect.namespace} explicitly: the
     * runtime build and the tool warm-up do it in the runtime starter, the seed
     * loaders in the application. A single-namespace host installs this starter
     * not at all and gets a fixed scope instead.
     */
    @Bean
    @ConditionalOnMissingBean(ScopeSupplier.class)
    ThreadBoundScope scopeSupplier() {
        // Strict: a thread that works unbound is a bug, not a request for the default namespace.
        // Requests bind through the namespace filter, tasks through the runtime's advisor, start-up
        // routines explicitly in mindconnect.namespace (see the runtime starter and the seed loaders).
        return ThreadBoundScope.strict();
    }

    /** The namespaces of the installation under {@code <mindconnect.data.base-dir>/system/namespaces}. */
    @Bean
    @ConditionalOnMissingBean(NamespaceRepository.class)
    @ConditionalOnProperty(name = "mindconnect.persistence", havingValue = "file", matchIfMissing = true)
    NamespaceRepository fileNamespaceRepository(@Value("${mindconnect.data.base-dir:data}") String baseDir,
                                                ObjectMapper objectMapper,
                                                ObjectProvider<EncryptionHelper> encryption) {
        return encrypting(new FileNamespaceRepository(Path.of(baseDir), objectMapper), encryption);
    }

    /** A namespace's variables are secrets — {@code enc:} at rest when the application has a key, like LLM credentials. */
    static NamespaceRepository encrypting(NamespaceRepository repository, ObjectProvider<EncryptionHelper> encryption) {
        EncryptionHelper helper = encryption.getIfAvailable();
        if (helper == null) {
            log.warn("No EncryptionHelper — a namespace's variables are stored unencrypted");
            return repository;
        }
        return new EncryptingNamespaceRepository(repository, helper);
    }

    /** Deleting a namespace removes {@code <mindconnect.data.base-dir>/<namespace>} — everything of it. */
    @Bean
    @ConditionalOnProperty(name = "mindconnect.persistence", havingValue = "file", matchIfMissing = true)
    NamespacePurge fileNamespacePurge(@Value("${mindconnect.data.base-dir:data}") String baseDir) {
        return new FileNamespacePurge(Path.of(baseDir));
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "ai.mindconnect.namespace.adapter.pg.PgNamespaceRepository")
    @ConditionalOnProperty(name = "mindconnect.persistence", havingValue = "postgres")
    static class Postgres {
        /** One row per namespace in {@code mc_namespace}, on the persistence starter's {@code Sql}. */
        @Bean
        @ConditionalOnMissingBean(NamespaceRepository.class)
        NamespaceRepository pgNamespaceRepository(ai.mindconnect.jdbc.Sql mindconnectSql,
                                                  ObjectProvider<EncryptionHelper> encryption) {
            return encrypting(new ai.mindconnect.namespace.adapter.pg.PgNamespaceRepository(mindconnectSql).initSchema(),
                    encryption);
        }

        /** Deleting a namespace removes its rows from every namespaced table and drops its pgvector tables. */
        @Bean
        NamespacePurge pgNamespacePurge(ai.mindconnect.jdbc.Sql mindconnectSql) {
            return new ai.mindconnect.namespace.adapter.pg.PgNamespacePurge(mindconnectSql);
        }
    }

    /** The default namespace — open to everyone — is {@code mindconnect.namespace}, like the fallback above. */
    @Bean
    @ConditionalOnMissingBean
    NamespaceService namespaceService(NamespaceRepository namespaces,
                                      @Value("${mindconnect.namespace:local}") String defaultNamespace,
                                      ObjectProvider<NamespacePurge> purges) {
        return new NamespaceService(namespaces, new Namespace(defaultNamespace), java.time.Clock.systemUTC(),
                purges.orderedStream().toList());
    }

    /**
     * Where {@code ${VAR}} placeholders — an LLM config's API key, say — get their
     * values on this server: what the user behind the work stored for themselves,
     * else what the namespace they work in stores, else the process environment.
     * A host that resolves differently defines its own {@link EnvVarResolver}.
     */
    @Bean
    @ConditionalOnMissingBean(EnvVarResolver.class)
    EnvVarResolver envVarResolver(ScopeSupplier scope, NamespaceRepository namespaces,
                                  ObjectProvider<UserRepository> users) {
        List<EnvVarResolver> sources = new ArrayList<>();
        UserRepository userRepository = users.getIfAvailable();
        if (userRepository != null) sources.add(new UserEnvVarResolver(userRepository, scope));
        sources.add(new NamespaceEnvVarResolver(namespaces, scope));
        sources.add(EnvVarResolver.system());
        return EnvVarResolver.chain(sources);
    }

    /** Before Spring Security ({@code -100}): matchers must see the path without the prefix. */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    FilterRegistrationBean<NamespacePathFilter> namespacePathFilter() {
        FilterRegistrationBean<NamespacePathFilter> registration = new FilterRegistrationBean<>(new NamespacePathFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setName("namespacePathFilter");
        return registration;
    }

    /** Right after Spring Security: the authentication is known, the scope can be bound. */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    FilterRegistrationBean<ScopeBindingFilter> scopeBindingFilter(ScopeSupplier scope, NamespaceService namespaces,
                                                                  ObjectProvider<UserService> users) {
        FilterRegistrationBean<ScopeBindingFilter> registration =
                new FilterRegistrationBean<>(new ScopeBindingFilter(scope, namespaces, users.getIfAvailable()));
        registration.setOrder(SecurityProperties.DEFAULT_FILTER_ORDER + 10);
        registration.setName("scopeBindingFilter");
        return registration;
    }
}
