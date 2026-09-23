package ai.mindconnect.extension.spring;

import ai.mindconnect.agent.NamespaceRouted;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.runtime.feature.RuntimeFeature;
import ai.mindconnect.agent.tool.MultiToolProvider;
import ai.mindconnect.agent.tool.ToolFactory;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.extension.adapter.classpath.ClasspathAudit;
import ai.mindconnect.extension.adapter.classpath.ClasspathManifests;
import ai.mindconnect.extension.adapter.classpath.ContributionChecker;
import ai.mindconnect.extension.adapter.file.FileBrandActivationRepository;
import ai.mindconnect.extension.adapter.file.FileExtensionActivationRepository;
import ai.mindconnect.extension.domain.ExtensionRegistry;
import ai.mindconnect.extension.port.out.BrandActivationRepository;
import ai.mindconnect.extension.port.out.ExtensionActivationRepository;
import ai.mindconnect.extension.runtime.ExtensionsFeature;
import ai.mindconnect.extension.service.ExtensionService;
import ai.mindconnect.namespace.domain.NamespaceDefinition;
import ai.mindconnect.namespace.service.NamespaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Wires the extensions of an installation: the manifests on the classpath
 * become the {@link ExtensionRegistry}, the audit names what the classpath
 * offers without one, a namespace's decisions live in the persistence the
 * installation runs on, and the {@link ExtensionService} answers everybody
 * who asks whether an extension is on.
 *
 * <p>{@code mindconnect.extensions.strict} decides what a problem means:
 * {@code false} (the default, while not every module has a manifest) logs it
 * and starts, {@code true} refuses to start — for an installation that wants
 * nothing on its classpath it has not been told about.
 */
@AutoConfiguration(afterName = {
        "ai.mindconnect.agent.starter.file.FilePersistenceAutoConfiguration",
        "ai.mindconnect.agent.starter.postgres.PostgresPersistenceConfig",
        "ai.mindconnect.agent.starter.namespace.NamespaceAutoConfiguration"})
public class ExtensionAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ExtensionAutoConfiguration.class);

    /** The service interfaces the host loads from the classpath — what the audit looks at. */
    static final List<Class<?>> CLASSPATH_SPIS = List.of(ToolFactory.class, MultiToolProvider.class, RuntimeFeature.class);

    @Bean
    @ConditionalOnMissingBean
    ExtensionRegistry extensionRegistry(@Value("${mindconnect.extensions.strict:false}") boolean strict) {
        ExtensionRegistry registry = ClasspathManifests.load(Thread.currentThread().getContextClassLoader());
        if (registry.hasProblems()) {
            for (ExtensionRegistry.Problem problem : registry.problems()) {
                log.warn("Extension problem: {}", problem);
            }
            if (strict) {
                throw new IllegalStateException("Refusing to start with " + registry.problems().size()
                        + " extension problem(s) (mindconnect.extensions.strict=true): " + registry.problems());
            }
        }
        log.info("Extensions: {} found on the classpath", registry.size());
        return registry;
    }

    @Bean
    @ConditionalOnMissingBean
    ExtensionAudit extensionAudit(ExtensionRegistry registry,
                                  @Value("${mindconnect.extensions.strict:false}") boolean strict) {
        var unmanaged = ClasspathAudit.unmanaged(Thread.currentThread().getContextClassLoader(), CLASSPATH_SPIS, registry);
        for (ClasspathAudit.UnmanagedJar jar : unmanaged) {
            log.warn("Extension audit: {} brings {} without a {} — declare it, or it stays unmanaged",
                    jar.jar(), jar.providers(), ClasspathManifests.RESOURCE);
        }
        if (strict && !unmanaged.isEmpty()) {
            throw new IllegalStateException("Refusing to start with " + unmanaged.size()
                    + " jar(s) that bring providers without a manifest (mindconnect.extensions.strict=true): "
                    + unmanaged.stream().map(ClasspathAudit.UnmanagedJar::jar).toList());
        }
        return new ExtensionAudit(unmanaged);
    }

    /** A namespace's decisions under {@code <mindconnect.data.base-dir>/<namespace>/system/extensions}. */
    @Bean
    @ConditionalOnMissingBean(ExtensionActivationRepository.class)
    @ConditionalOnProperty(name = "mindconnect.persistence", havingValue = "file", matchIfMissing = true)
    ExtensionActivationRepository fileExtensionActivationRepository(
            @Value("${mindconnect.data.base-dir:data}") String baseDir,
            ObjectMapper objectMapper,
            ObjectProvider<ScopeSupplier> scope) {
        Path storage = Path.of(baseDir);
        return NamespaceRouted.route(ExtensionActivationRepository.class, scope.getIfAvailable(ScopeSupplier::local),
                ns -> new FileExtensionActivationRepository(storage, objectMapper, ns));
    }

    /** The brands' decisions under {@code <mindconnect.data.base-dir>/system/extension-brands}. */
    @Bean
    @ConditionalOnMissingBean(BrandActivationRepository.class)
    @ConditionalOnProperty(name = "mindconnect.persistence", havingValue = "file", matchIfMissing = true)
    BrandActivationRepository fileBrandActivationRepository(@Value("${mindconnect.data.base-dir:data}") String baseDir,
                                                            ObjectMapper objectMapper) {
        return new FileBrandActivationRepository(Path.of(baseDir), objectMapper);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "ai.mindconnect.extension.adapter.pg.PgExtensionActivationRepository")
    @ConditionalOnProperty(name = "mindconnect.persistence", havingValue = "postgres")
    static class Postgres {
        /** One row per decision in {@code mc_extension_activation}, on the persistence starter's {@code Sql}. */
        @Bean
        @ConditionalOnMissingBean(ExtensionActivationRepository.class)
        ExtensionActivationRepository pgExtensionActivationRepository(ai.mindconnect.jdbc.Sql mindconnectSql,
                                                                      ObjectProvider<ScopeSupplier> scope) {
            return NamespaceRouted.route(ExtensionActivationRepository.class, scope.getIfAvailable(ScopeSupplier::local),
                    ns -> new ai.mindconnect.extension.adapter.pg.PgExtensionActivationRepository(mindconnectSql, ns)
                            .initSchema());
        }

        /** One row per brand decision in {@code mc_extension_brand_activation}. */
        @Bean
        @ConditionalOnMissingBean(BrandActivationRepository.class)
        BrandActivationRepository pgBrandActivationRepository(ai.mindconnect.jdbc.Sql mindconnectSql) {
            return new ai.mindconnect.extension.adapter.pg.PgBrandActivationRepository(mindconnectSql).initSchema();
        }
    }

    /**
     * The service, told the brand of the namespace at hand — from the
     * namespace service, per call — and the extensions the operator switched
     * off everywhere ({@code mindconnect.extensions.disabled}, comma-separated).
     * Without a namespace service there are no brands.
     */
    @Bean
    @ConditionalOnMissingBean
    ExtensionService extensionService(ExtensionRegistry registry, ExtensionActivationRepository activations,
                                      BrandActivationRepository brands,
                                      ObjectProvider<NamespaceService> namespaces,
                                      ObjectProvider<ScopeSupplier> scope,
                                      @Value("${mindconnect.extensions.disabled:}") String disabled) {
        Set<String> disabledByOperator = Arrays.stream(disabled.split(","))
                .map(String::strip).filter(id -> !id.isEmpty()).collect(Collectors.toSet());
        if (!disabledByOperator.isEmpty()) {
            log.info("Extensions switched off by the operator: {}", disabledByOperator);
        }
        return new ExtensionService(registry, activations, brands, () -> {
            NamespaceService service = namespaces.getIfAvailable();
            ScopeSupplier current = scope.getIfAvailable();
            if (service == null || current == null) return Optional.empty();
            return service.find(current.namespace()).map(NamespaceDefinition::brand).map(Namespace::value);
        }, disabledByOperator);
    }

    /**
     * Holds each manifest against the classpath for the Extensions screen.
     * The tool names come from the registry at the bottom of the stack — the
     * one that knows everything the classpath offers, before any namespace's
     * decision or operator's setting took something away.
     */
    @Bean
    @ConditionalOnMissingBean
    ContributionChecker contributionChecker(ObjectProvider<ToolRegistry> toolRegistry) {
        return new ContributionChecker(Thread.currentThread().getContextClassLoader(), () -> {
            ToolRegistry registry = toolRegistry.getIfAvailable();
            return registry == null ? Set.of() : registry.source().knownToolNames();
        });
    }

    /** The runtime feature that lets a namespace's decisions reach the tool registry. */
    @Bean
    @ConditionalOnMissingBean
    ExtensionsFeature extensionsFeature(ExtensionService extensions) {
        return new ExtensionsFeature(extensions);
    }
}
