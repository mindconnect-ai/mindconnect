package ai.mindconnect.adminui.service;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.NamespaceFirstUse;
import ai.mindconnect.agent.Scope;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.ThreadBoundScope;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.runtime.skill.SkillRepository;
import ai.mindconnect.extension.port.out.InstalledSeedRepository;
import ai.mindconnect.extension.service.ExtensionService;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.namespace.service.NamespaceService;
import ai.mindconnect.workflow.persistence.port.WorkflowDataRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Gives every namespace the bundled content it should have — the first time
 * the namespace is used after start, once per namespace and process.
 *
 * <p>The seam is the {@link ThreadBoundScope}: every entry point binds the
 * namespace it works in there (the request filter, for admins and plain users
 * alike; the task advisor; the start-up routines), and this registers a
 * {@link NamespaceFirstUse} with it once every bean exists. The first binding
 * of a namespace runs the {@link NamespaceSeeder} in it before the work that
 * bound it; every later one costs a set lookup. The start-up namespace goes
 * through the same path, from {@code InitialDataLoader} at start.
 *
 * <p>A switch on the Extensions screen can make content due that was not:
 * {@link #extensionsChanged()} runs the seeder in the namespace at hand right
 * away and lets every other namespace run it again on its next use — a
 * brand's decision reaches all of the brand's namespaces that way.
 *
 * <p>The seeding runs as the installation, not as whoever's request got there
 * first: the namespace stays bound, the user is taken off for its duration.
 * A plain user's request is as good a first use as an admin's, and the write
 * guard lets work with nobody behind it through.
 *
 * <p>Only namespaces that exist are seeded: a queued task of a namespace that
 * was deleted meanwhile must not bring its agents back.
 */
@Component
public class NamespaceSeeding implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(NamespaceSeeding.class);

    private final NamespaceSeeder seeder;
    private final ObjectProvider<ScopeSupplier> scope;
    private final ObjectProvider<NamespaceService> namespaces;
    private final NamespaceFirstUse firstUse = new NamespaceFirstUse("Seeding", this::seed);
    /** What the seeder did on this thread — for {@link #extensionsChanged()} to report. */
    private final ThreadLocal<NamespaceSeeder.Report> ranHere = new ThreadLocal<>();

    @Autowired
    public NamespaceSeeding(LlmConfigRepository llmConfigs, AgentDefinitionRepository agents,
                            SkillRepository skills, WorkflowDataRepository workflows,
                            InstalledSeedRepository installedSeeds, ExtensionService extensions,
                            ObjectMapper objectMapper, ObjectProvider<ScopeSupplier> scope,
                            ObjectProvider<NamespaceService> namespaces) {
        this(new NamespaceSeeder(new BundledSeeds(objectMapper), llmConfigs, agents, skills, workflows,
                installedSeeds, extensions), scope, namespaces);
    }

    NamespaceSeeding(NamespaceSeeder seeder, ObjectProvider<ScopeSupplier> scope,
                     ObjectProvider<NamespaceService> namespaces) {
        this.seeder = seeder;
        this.scope = scope;
        this.namespaces = namespaces;
    }

    /** Hooks into the scope once every bean is there — a binding before would find repositories half built. */
    @Override
    public void afterSingletonsInstantiated() {
        if (scope.getIfAvailable() instanceof ThreadBoundScope bound) {
            bound.onBind(entered -> ensure(entered.namespace()));
        }
    }

    /**
     * Seeds {@code namespace} unless that happened already in this process. The
     * caller has it bound — the start-up loader for the start-up namespace, a
     * host with a fixed scope for its one namespace.
     */
    public void ensure(Namespace namespace) {
        try {
            firstUse.ensure(namespace);
        } finally {
            ranHere.remove();
        }
    }

    /**
     * An extension was switched (on, off, back to default, for the namespace
     * or the brand): seeds the namespace at hand now, and every other one on its
     * next use.
     *
     * @return what the run in the namespace at hand installed; empty without a bound namespace
     */
    public Optional<NamespaceSeeder.Report> extensionsChanged() {
        ScopeSupplier current = scope.getIfAvailable();
        if (current == null) return Optional.empty();
        Namespace namespace;
        try {
            namespace = current.namespace();
        } catch (IllegalStateException unbound) {
            return Optional.empty();
        }
        firstUse.forgetAll();
        try {
            firstUse.ensure(namespace);
            return Optional.ofNullable(ranHere.get());
        } finally {
            ranHere.remove();
        }
    }

    private void seed(Namespace namespace) {
        NamespaceService service = namespaces.getIfAvailable();
        if (service != null && service.find(namespace).isEmpty()) {
            log.debug("Namespace '{}' does not exist (any more) — nothing to seed", namespace.value());
            return;
        }
        ranHere.set(scope.getIfAvailable() instanceof ThreadBoundScope bound
                // Same namespace: no new entry, so this does not trigger the seeding again.
                ? bound.runIn(Scope.of(namespace), () -> seeder.installMissing(namespace))
                : seeder.installMissing(namespace));
    }
}
