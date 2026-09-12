package ai.mindconnect.agent.registry.spring;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.registry.adapter.file.FileRegistrySourceRepository;
import ai.mindconnect.agent.registry.adapter.github.GitHubRegistryClient;
import ai.mindconnect.agent.registry.adapter.installer.AgentDefinitionInstaller;
import ai.mindconnect.agent.registry.adapter.installer.LlmConfigInstaller;
import ai.mindconnect.agent.registry.domain.RegistrySource;
import ai.mindconnect.agent.registry.port.out.RegistryClient;
import ai.mindconnect.agent.registry.port.out.RegistryInstaller;
import ai.mindconnect.agent.registry.port.out.RegistrySourceRepository;
import ai.mindconnect.agent.registry.service.RegistryService;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Wires the registry into a host application: the store of configured
 * registries, the GitHub client, the installers for the entities this module
 * can see, and the service over them.
 *
 * <p>Off by nothing and on by default — reading a registry happens only when
 * somebody opens the screen, and an installation with no sources configured
 * reads nothing at all. {@code mindconnect.registry.enabled=false} takes the
 * whole thing away for an installation that wants no outbound calls even as a
 * possibility.
 *
 * <p>{@code mindconnect.registry.default-source} seeds one registry on first
 * start ({@code owner/repo[@ref][:index-path]}) — how a distribution points a
 * fresh installation at its own catalogue without shipping a file.
 *
 * <p>Ordered after the persistence starters: the installers are conditional on
 * the repositories those register, and a {@code @ConditionalOnBean} evaluated
 * before them finds nothing — the screen then offers every entry and can
 * import none.
 */
@AutoConfiguration(afterName = {
        "ai.mindconnect.agent.starter.file.FilePersistenceAutoConfiguration",
        "ai.mindconnect.agent.starter.postgres.PostgresPersistenceConfig"})
@ConditionalOnProperty(prefix = "mindconnect.registry", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class RegistryAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RegistryAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public RegistrySourceRepository registrySourceRepository(
            @Value("${mindconnect.data.base-dir:./data}") String dataBaseDir,
            @Value("${mindconnect.registry.default-source:}") String defaultSource,
            ObjectProvider<Namespace> namespace) {
        FileRegistrySourceRepository repository = new FileRegistrySourceRepository(
                Path.of(dataBaseDir), namespace.getIfAvailable(() -> Namespace.DEFAULT));
        seed(repository, defaultSource);
        return repository;
    }

    /**
     * Adds the configured default registry unless it is already there. Only
     * ever adds: an operator who deleted it meant to, and a start that puts it
     * back would be a bug report.
     */
    private void seed(RegistrySourceRepository repository, String defaultSource) {
        if (defaultSource == null || defaultSource.isBlank()) {
            return;
        }
        try {
            RegistrySource source = RegistrySource.of(defaultSource);
            if (repository.findById(source.id()).isEmpty() && repository.findAll().isEmpty()) {
                repository.save(source);
                log.info("Seeded the default registry {}", source.coordinates());
            }
        } catch (RuntimeException e) {
            log.warn("mindconnect.registry.default-source '{}' is not a registry: {}",
                    defaultSource, e.getMessage());
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public RegistryClient registryClient(
            @Value("${mindconnect.registry.cache-ttl:PT10M}") Duration cacheTtl,
            @Value("${mindconnect.registry.timeout:PT20S}") Duration timeout) {
        return new GitHubRegistryClient(cacheTtl, timeout);
    }

    @Bean
    @ConditionalOnBean(LlmConfigRepository.class)
    @ConditionalOnMissingBean
    public LlmConfigInstaller llmConfigRegistryInstaller(LlmConfigRepository repository) {
        return new LlmConfigInstaller(repository);
    }

    @Bean
    @ConditionalOnBean(AgentDefinitionRepository.class)
    @ConditionalOnMissingBean
    public AgentDefinitionInstaller agentDefinitionRegistryInstaller(AgentDefinitionRepository repository) {
        return new AgentDefinitionInstaller(repository);
    }

    /**
     * The service over whatever installers the host assembled — the two here,
     * plus any another module contributed (the workflow installer lives with
     * the workflow tools). A host with no installers at all still gets the
     * service: browsing a registry is useful before anything can be installed,
     * and every entry then reports why it cannot be.
     */
    @Bean
    @ConditionalOnMissingBean
    public RegistryService registryService(RegistrySourceRepository sources, RegistryClient client,
                                           ObjectProvider<RegistryInstaller> installers) {
        return new RegistryService(sources, client, installers.orderedStream().toList());
    }
}
