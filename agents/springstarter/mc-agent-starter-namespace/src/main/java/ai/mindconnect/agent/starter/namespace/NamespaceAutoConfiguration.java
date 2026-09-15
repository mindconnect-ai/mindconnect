package ai.mindconnect.agent.starter.namespace;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.Scope;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.ThreadBoundScope;
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

    /**
     * Where this server works: a {@link Scope} bound per request by the
     * {@link ScopeBindingFilter} and per queued task by the runtime's task
     * advisor. A thread neither binds — a start-up routine, a stream's
     * scheduler — works in {@code mindconnect.namespace} (default
     * {@code local}) until those bind too and the fallback goes.
     */
    @Bean
    @ConditionalOnMissingBean(ScopeSupplier.class)
    ThreadBoundScope scopeSupplier(@Value("${mindconnect.namespace:local}") String fallbackNamespace) {
        return ThreadBoundScope.withFallback(Scope.of(new Namespace(fallbackNamespace)));
    }

    /** The namespaces of the installation under {@code <mindconnect.data.base-dir>/system/namespaces}. */
    @Bean
    @ConditionalOnMissingBean(NamespaceRepository.class)
    @ConditionalOnProperty(name = "mindconnect.persistence", havingValue = "file", matchIfMissing = true)
    NamespaceRepository fileNamespaceRepository(@Value("${mindconnect.data.base-dir:data}") String baseDir,
                                                ObjectMapper objectMapper) {
        return new FileNamespaceRepository(Path.of(baseDir), objectMapper);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "ai.mindconnect.namespace.adapter.pg.PgNamespaceRepository")
    @ConditionalOnProperty(name = "mindconnect.persistence", havingValue = "postgres")
    static class Postgres {
        /** One row per namespace in {@code mc_namespace}, on the persistence starter's {@code Sql}. */
        @Bean
        @ConditionalOnMissingBean(NamespaceRepository.class)
        NamespaceRepository pgNamespaceRepository(ai.mindconnect.jdbc.Sql mindconnectSql) {
            return new ai.mindconnect.namespace.adapter.pg.PgNamespaceRepository(mindconnectSql).initSchema();
        }
    }

    /** The default namespace — open to everyone — is {@code mindconnect.namespace}, like the fallback above. */
    @Bean
    @ConditionalOnMissingBean
    NamespaceService namespaceService(NamespaceRepository namespaces,
                                      @Value("${mindconnect.namespace:local}") String defaultNamespace) {
        return new NamespaceService(namespaces, new Namespace(defaultNamespace));
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
