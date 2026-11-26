package ai.mindconnect.agent.starter.namespace;

import ai.mindconnect.namespace.domain.Email;
import ai.mindconnect.agent.scope.Namespace;
import ai.mindconnect.agent.scope.Scope;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.agent.sdk.AgentRuntime;
import ai.mindconnect.jdbc.Sql;
import ai.mindconnect.namespace.adapter.pg.PgNamespacePurge;
import ai.mindconnect.namespace.runtime.FileNamespacePlugin;
import ai.mindconnect.namespace.runtime.NamespacePlugin;
import ai.mindconnect.namespace.runtime.pg.PgNamespacePlugin;
import ai.mindconnect.namespace.port.out.NamespacePurge;
import ai.mindconnect.namespace.service.ThreadBoundScopeSupplier;
import java.util.List;
import java.util.Optional;
import ai.mindconnect.namespace.adapter.file.FileNamespacePurge;
import ai.mindconnect.namespace.port.out.NamespaceRepository;
import ai.mindconnect.namespace.service.NamespaceService;
import ai.mindconnect.namespace.port.out.UserEmails;
import ai.mindconnect.user.domain.User;
import ai.mindconnect.user.service.UserService;
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
 * may work in which, and the {@link ThreadBoundScopeSupplier} every store reads.
 *
 * <p>Auto-configured ahead of the persistence starters so that the scope
 * supplier defined here is the one they route by. The namespace repository
 * follows {@code mindconnect.persistence} like every other store, but is
 * installation-wide: it is the store that says which namespaces exist. It
 * lives in the runtime, as a {@link NamespacePlugin}, because the runtime
 * resolves a namespace's variables; the beans here hand its services out.
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
    ThreadBoundScopeSupplier scopeSupplier() {
        // Strict: a thread that works unbound is a bug, not a request for the default namespace.
        // Requests bind through the namespace filter, tasks through the runtime's advisor, start-up
        // routines explicitly in mindconnect.namespace (see the runtime starter and the seed loaders).
        return ThreadBoundScopeSupplier.strict();
    }

    /**
     * The namespaces of the installation under {@code <mindconnect.data.base-dir>/system/namespaces},
     * installed into the runtime: the runtime resolves a namespace's variables, so it holds the store.
     */
    @Bean
    @ConditionalOnMissingBean(NamespacePlugin.class)
    @ConditionalOnProperty(name = "mindconnect.persistence", havingValue = "file", matchIfMissing = true)
    FileNamespacePlugin fileNamespacePlugin(@Value("${mindconnect.data.base-dir:data}") String baseDir,
                                            ObjectProvider<EncryptionHelper> encryption,
                                            @Value("${mindconnect.namespace:local}") String defaultNamespace,
                                            @Value("${mindconnect.email-domain:}") String emailDomain,
                                            @Value("${mindconnect.namespace-admins:}") List<String> defaultAdmins,
                                            UserEmails emails, ObjectProvider<NamespacePurge> purges) {
        return new FileNamespacePlugin(Path.of(baseDir), encryption.getIfAvailable(),
                new Namespace(defaultNamespace), emailDomain, admins(defaultAdmins, emailDomain), emails,
                () -> purges.orderedStream().toList());
    }

    /**
     * Who shapes the default namespace is {@code mindconnect.namespace-admins} — and naming
     * anybody there closes it: an installation that lists its people has said that being signed
     * in is not the same as being let in. Unset it stays open to every signed-in user, which is
     * what a single-user installation, the dev mode and the tests work with.
     */
    static List<Email> admins(List<String> defaultAdmins, String emailDomain) {
        return defaultAdmins.stream().map(entry -> Email.qualified(entry, emailDomain)).toList();
    }

    /** Deleting a namespace removes {@code <mindconnect.data.base-dir>/<namespace>} — everything of it. */
    @Bean
    @ConditionalOnProperty(name = "mindconnect.persistence", havingValue = "file", matchIfMissing = true)
    NamespacePurge fileNamespacePurge(@Value("${mindconnect.data.base-dir:data}") String baseDir) {
        return new FileNamespacePurge(Path.of(baseDir));
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "ai.mindconnect.namespace.runtime.pg.PgNamespacePlugin")
    @ConditionalOnProperty(name = "mindconnect.persistence", havingValue = "postgres")
    static class Postgres {
        /** One row per namespace in {@code mc_namespace}, on the runtime's {@code Sql}. */
        @Bean
        @ConditionalOnMissingBean(NamespacePlugin.class)
        PgNamespacePlugin pgNamespacePlugin(ObjectProvider<EncryptionHelper> encryption,
                                            @Value("${mindconnect.namespace:local}") String defaultNamespace,
                                            @Value("${mindconnect.email-domain:}") String emailDomain,
                                            @Value("${mindconnect.namespace-admins:}") List<String> defaultAdmins,
                                            UserEmails emails, ObjectProvider<NamespacePurge> purges) {
            return new PgNamespacePlugin(encryption.getIfAvailable(), new Namespace(defaultNamespace), emailDomain,
                    admins(defaultAdmins, emailDomain), emails, () -> purges.orderedStream().toList());
        }

        /** Deleting a namespace removes its rows from every namespaced table and drops its pgvector tables. */
        @Bean
        NamespacePurge pgNamespacePurge(Sql mindconnectSql) {
            return new PgNamespacePurge(mindconnectSql);
        }
    }

    /** The runtime's namespaces, for the application's own beans. */
    @Bean NamespaceRepository namespaceRepository(AgentRuntime r) { return r.service(NamespaceRepository.class); }
    @Bean NamespaceService namespaceService(AgentRuntime r) { return r.service(NamespaceService.class); }

    /**
     * Namespaces list people by e-mail; a request carries a user id. The
     * addresses come from the user store, asked per call — the user service is
     * the runtime's, made after this; without one — a library host, a test —
     * nobody has a stored address and the service falls back to the id at
     * {@code mindconnect.email-domain}.
     */
    @Bean
    @ConditionalOnMissingBean
    UserEmails userEmails(ObjectProvider<UserService> users) {
        return id -> Optional.ofNullable(users.getIfAvailable())
                .flatMap(userService -> userService.find(id))
                .map(User::email)
                .flatMap(Email::parse);
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

    /**
     * Right after Spring Security: the authentication is known, the scope can be
     * bound. A {@link HostNamespaces} bean — the Admin UI contributes one from
     * its branding — makes the address bar decide where the work happens.
     */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    FilterRegistrationBean<ScopeBindingFilter> scopeBindingFilter(ScopeSupplier scope, NamespaceService namespaces,
                                                                  ObjectProvider<UserService> users,
                                                                  ObjectProvider<HostNamespaces> hosts) {
        FilterRegistrationBean<ScopeBindingFilter> registration = new FilterRegistrationBean<>(
                new ScopeBindingFilter(scope, namespaces, users.getIfAvailable(),
                        hosts.getIfAvailable(HostNamespaces::none)));
        registration.setOrder(SecurityProperties.DEFAULT_FILTER_ORDER + 10);
        registration.setName("scopeBindingFilter");
        return registration;
    }
}
