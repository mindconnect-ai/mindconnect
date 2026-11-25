package ai.mindconnect.agent.starter.extension;

import ai.mindconnect.plugin.Plugin;
import ai.mindconnect.agent.tool.MultiToolProvider;
import ai.mindconnect.agent.tool.ToolFactory;
import ai.mindconnect.agent.tool.ToolRegistry;
import ai.mindconnect.extension.adapter.classpath.ClasspathAudit;
import ai.mindconnect.extension.adapter.classpath.ClasspathManifests;
import ai.mindconnect.extension.adapter.classpath.ContributionChecker;
import ai.mindconnect.extension.adapter.classpath.ExtensionAudit;
import ai.mindconnect.extension.domain.ExtensionRegistry;
import ai.mindconnect.extension.port.out.BrandActivationRepository;
import ai.mindconnect.extension.port.out.ExtensionActivationRepository;
import ai.mindconnect.extension.port.out.InstalledSeedRepository;
import ai.mindconnect.agent.sdk.AgentRuntime;
import ai.mindconnect.extension.runtime.ExtensionPlugin;
import ai.mindconnect.extension.runtime.FileExtensionPlugin;
import ai.mindconnect.extension.runtime.pg.PgExtensionPlugin;
import ai.mindconnect.extension.service.ExtensionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Wires the extensions of an installation: the manifests on the classpath
 * become the {@link ExtensionRegistry}, the audit names what the classpath
 * offers without one, a namespace's decisions live in the persistence the
 * installation runs on, and the {@link ExtensionService} answers everybody
 * who asks whether an extension is on. Registry, stores and service live in
 * the runtime, as an {@link ExtensionPlugin}, because the runtime asks the
 * service while it resolves tools; the beans here hand them out.
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
    static final List<Class<?>> CLASSPATH_SPIS = List.of(ToolFactory.class, MultiToolProvider.class, Plugin.class);

    /**
     * The extensions on files, installed into the runtime: the runtime asks the
     * service while it resolves tools, so it holds the manifests and the stores.
     */
    @Bean
    @ConditionalOnMissingBean(ExtensionPlugin.class)
    @ConditionalOnProperty(name = "mindconnect.persistence", havingValue = "file", matchIfMissing = true)
    FileExtensionPlugin fileExtensionPlugin(@Value("${mindconnect.data.base-dir:data}") String baseDir,
                                            @Value("${mindconnect.extensions.strict:false}") boolean strict,
                                            @Value("${mindconnect.extensions.disabled:}") String disabled) {
        return new FileExtensionPlugin(Path.of(baseDir), strict, disabledByOperator(disabled));
    }

    /** The extensions in Postgres, on the runtime's {@code Sql}. */
    @Bean
    @ConditionalOnMissingBean(ExtensionPlugin.class)
    @ConditionalOnProperty(name = "mindconnect.persistence", havingValue = "postgres")
    PgExtensionPlugin pgExtensionPlugin(@Value("${mindconnect.extensions.strict:false}") boolean strict,
                                        @Value("${mindconnect.extensions.disabled:}") String disabled) {
        return new PgExtensionPlugin(strict, disabledByOperator(disabled));
    }

    /** The extensions the operator switched off everywhere ({@code mindconnect.extensions.disabled}, comma-separated). */
    private static Set<String> disabledByOperator(String disabled) {
        Set<String> disabledByOperator = Arrays.stream(disabled.split(","))
                .map(String::strip).filter(id -> !id.isEmpty()).collect(Collectors.toSet());
        if (!disabledByOperator.isEmpty()) {
            log.info("Extensions switched off by the operator: {}", disabledByOperator);
        }
        return disabledByOperator;
    }

    // ── the runtime's extensions, for the application's own beans ───────────

    @Bean ExtensionRegistry extensionRegistry(AgentRuntime r) { return r.service(ExtensionRegistry.class); }
    @Bean ExtensionService extensionService(AgentRuntime r) { return r.service(ExtensionService.class); }
    @Bean ExtensionActivationRepository extensionActivationRepository(AgentRuntime r) {
        return r.service(ExtensionActivationRepository.class);
    }
    @Bean InstalledSeedRepository installedSeedRepository(AgentRuntime r) { return r.service(InstalledSeedRepository.class); }
    @Bean BrandActivationRepository brandActivationRepository(AgentRuntime r) {
        return r.service(BrandActivationRepository.class);
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
}
