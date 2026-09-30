package ai.mindconnect.adminui.service;

import ai.mindconnect.adminui.namespaces.GuardedRepositories;
import ai.mindconnect.adminui.namespaces.NamespaceWriteGuard;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.NamespaceRouted;
import ai.mindconnect.agent.Scope;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.ThreadBoundScope;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.adapter.repo.memory.InMemoryAgentDefinitionRepository;
import ai.mindconnect.agent.runtime.adapter.repo.memory.InMemorySkillRepository;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.runtime.skill.SkillRepository;
import ai.mindconnect.extension.adapter.memory.InMemoryExtensionActivationRepository;
import ai.mindconnect.extension.adapter.memory.InMemoryInstalledSeedRepository;
import ai.mindconnect.extension.domain.Extension;
import ai.mindconnect.extension.domain.ExtensionActivation;
import ai.mindconnect.extension.domain.ExtensionId;
import ai.mindconnect.extension.domain.ExtensionManifest;
import ai.mindconnect.extension.domain.ExtensionRegistry;
import ai.mindconnect.extension.domain.InstalledSeed;
import ai.mindconnect.extension.port.out.ExtensionActivationRepository;
import ai.mindconnect.extension.port.out.InstalledSeedRepository;
import ai.mindconnect.extension.service.ExtensionService;
import ai.mindconnect.llm.adapter.memory.InMemoryLlmConfigRepository;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.namespace.adapter.memory.InMemoryNamespaceRepository;
import ai.mindconnect.namespace.service.NamespaceService;
import ai.mindconnect.workflow.persistence.memory.InMemoryWorkflowDataRepository;
import ai.mindconnect.workflow.persistence.port.WorkflowDataRepository;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The per-namespace seeding, end to end through a {@link ThreadBoundScope}
 * and routed in-memory stores: binding a namespace is what triggers it, as a
 * request does. The seeds are {@code seed-test/} on the test classpath: an
 * LLM config and an agent of the host, and two agents, a skill and a
 * workflow that the manifest of {@code seed-crm} declares.
 */
class NamespaceSeedingTest {

    private static final Namespace LOCAL = new Namespace("local");
    private static final Namespace ERNI = new Namespace("erni");
    private static final ExtensionId CRM = ExtensionId.of("seed-crm");
    private static final List<String> EVERYTHING = List.of(
            "llm-config:seed-test-llm", "agent:host-helper", "agent:crm-assistant", "agent:crm-checker",
            "skill:crm-notes", "workflow:crm-flow");

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /** What is stored, per namespace — outlives a "restart", which only builds the scope and the seeding anew. */
    private final Map<Namespace, InMemoryLlmConfigRepository> llmStores = new ConcurrentHashMap<>();
    private final Map<Namespace, InMemoryAgentDefinitionRepository> agentStores = new ConcurrentHashMap<>();
    private final Map<Namespace, InMemorySkillRepository> skillStores = new ConcurrentHashMap<>();
    private final Map<Namespace, InMemoryWorkflowDataRepository> workflowStores = new ConcurrentHashMap<>();
    private final Map<Namespace, InMemoryInstalledSeedRepository> ledgers = new ConcurrentHashMap<>();
    private final Map<Namespace, InMemoryExtensionActivationRepository> decisions = new ConcurrentHashMap<>();

    private ThreadBoundScope scope;
    private NamespaceSeeding seeding;
    private final AtomicInteger runs = new AtomicInteger();
    private NamespaceService namespaces;

    @BeforeEach
    void start() {
        namespaces = null;
        restart();
    }

    /** A new process: fresh scope, fresh routing, fresh seeding — the stored data stays. */
    private void restart() {
        scope = ThreadBoundScope.strict();
        // Guarded as the admin app guards them: "plain-user" is an admin of nothing but the open default.
        NamespaceWriteGuard guard = new NamespaceWriteGuard(
                new NamespaceService(new InMemoryNamespaceRepository(), LOCAL), scope);
        LlmConfigRepository llm = new GuardedRepositories.LlmConfigs(NamespaceRouted.route(LlmConfigRepository.class,
                scope, ns -> llmStores.computeIfAbsent(ns, n -> new InMemoryLlmConfigRepository())), guard);
        AgentDefinitionRepository agents = new GuardedRepositories.Agents(NamespaceRouted.route(
                AgentDefinitionRepository.class, scope,
                ns -> agentStores.computeIfAbsent(ns, n -> new InMemoryAgentDefinitionRepository())), guard);
        SkillRepository skills = new GuardedRepositories.Skills(NamespaceRouted.route(SkillRepository.class, scope,
                ns -> skillStores.computeIfAbsent(ns, n -> new InMemorySkillRepository())), guard);
        WorkflowDataRepository workflows = new GuardedRepositories.Workflows(NamespaceRouted.route(
                WorkflowDataRepository.class, scope,
                ns -> workflowStores.computeIfAbsent(ns, n -> new InMemoryWorkflowDataRepository())), guard);
        InstalledSeedRepository installed = NamespaceRouted.route(InstalledSeedRepository.class, scope,
                ns -> ledgers.computeIfAbsent(ns, n -> new InMemoryInstalledSeedRepository()));
        ExtensionActivationRepository activations = NamespaceRouted.route(ExtensionActivationRepository.class, scope,
                ns -> decisions.computeIfAbsent(ns, n -> new InMemoryExtensionActivationRepository()));
        ExtensionService extensions = new ExtensionService(new ExtensionRegistry(List.of(crmExtension())), activations);

        NamespaceSeeder seeder = new NamespaceSeeder(new BundledSeeds(mapper, "classpath*:seed-test/"),
                llm, agents, skills, workflows, installed, extensions) {
            @Override
            public Report installMissing(Namespace namespace) {
                runs.incrementAndGet();
                return super.installMissing(namespace);
            }
        };
        seeding = new NamespaceSeeding(seeder, provider(ScopeSupplier.class, scope),
                provider(NamespaceService.class, namespaces));
        seeding.afterSingletonsInstantiated();
        runs.set(0);
    }

    private static ExtensionManifest.Contributes contributes(ExtensionManifest.Content content) {
        return new ExtensionManifest.Contributes(null, null, content, null, null, null, null, null);
    }

    private static Extension crmExtension() {
        return new Extension(new ExtensionManifest(CRM, "Seed CRM", "1.0", null, null, null, null, true, null,
                contributes(new ExtensionManifest.Content(List.of("crm-assistant", "crm-checker"),
                        List.of("crm-notes"), List.of("crm-flow")))), "test");
    }

    private static <T> ObjectProvider<T> provider(Class<T> type, T bean) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (bean != null) beans.addBean(type.getSimpleName(), bean);
        return beans.getBeanProvider(type);
    }

    /**
     * Work in {@code namespace} as a plain user's request would: binding it is all
     * it takes. The user is an admin of nothing here but the open default
     * namespace, so every seed written elsewhere passes the write guard only
     * because the seeding runs as the installation.
     */
    private <T> T in(Namespace namespace, Supplier<T> body) {
        return scope.runIn(Scope.of(namespace, UserId.of("plain-user")), body);
    }

    private void use(Namespace namespace) {
        in(namespace, () -> null);
    }

    private List<String> agentNames(Namespace namespace) {
        return agentStores.getOrDefault(namespace, new InMemoryAgentDefinitionRepository()).findAll().stream()
                .map(AgentDefinition::name).sorted().toList();
    }

    private List<String> ledger(Namespace namespace) {
        return ledgers.getOrDefault(namespace, new InMemoryInstalledSeedRepository()).all().stream()
                .map(InstalledSeed::key).sorted().toList();
    }

    @Test
    void a_second_namespace_gets_what_the_first_got_on_its_first_use() {
        use(LOCAL);
        use(ERNI);

        for (Namespace namespace : List.of(LOCAL, ERNI)) {
            assertThat(agentNames(namespace)).as(namespace.value())
                    .containsExactly("crm-assistant", "crm-checker", "host-helper");
            assertThat(llmStores.get(namespace).findByName("seed-test-llm")).isPresent();
            assertThat(skillStores.get(namespace).findByName("crm-notes")).isPresent();
            assertThat(workflowStores.get(namespace).exists("crm-flow")).isTrue();
            assertThat(ledger(namespace)).containsExactlyInAnyOrderElementsOf(EVERYTHING);
        }
        assertThat(ledgers.get(ERNI).all()).filteredOn(seed -> seed.name().startsWith("crm-"))
                .extracting(InstalledSeed::source).containsOnly("seed-crm");
        assertThat(ledgers.get(ERNI).all()).filteredOn(seed -> seed.name().equals("host-helper"))
                .extracting(InstalledSeed::source).containsExactly(InstalledSeed.HOST);
    }

    @Test
    void a_record_that_is_there_is_left_as_it_is_and_counted_as_got() {
        AgentDefinition mine = AgentDefinition.create("crm-assistant", "Our own, edited", "Be terse.", null, "x");
        agentStores.computeIfAbsent(ERNI, n -> new InMemoryAgentDefinitionRepository()).save(mine);

        use(ERNI);

        assertThat(agentStores.get(ERNI).findByName("crm-assistant").orElseThrow().description())
                .isEqualTo("Our own, edited");
        assertThat(agentNames(ERNI)).containsExactly("crm-assistant", "crm-checker", "host-helper");
        assertThat(ledger(ERNI)).contains("agent:crm-assistant");
    }

    @Test
    void a_deleted_record_does_not_come_back() {
        use(ERNI);
        in(ERNI, () -> {
            AgentDefinition gone = agentStores.get(ERNI).findByName("crm-assistant").orElseThrow();
            agentStores.get(ERNI).deleteById(gone.id());
            return null;
        });

        restart();
        use(ERNI);
        in(ERNI, seeding::extensionsChanged);

        assertThat(runs).hasValue(2);
        assertThat(agentNames(ERNI)).containsExactly("crm-checker", "host-helper");
    }

    @Test
    void a_new_seed_reaches_a_namespace_that_was_seeded_before() {
        use(ERNI);
        // An older version shipped everything but crm-checker: the namespace never got it.
        ledgers.put(ERNI, new InMemoryInstalledSeedRepository());
        agentStores.get(ERNI).deleteById(agentStores.get(ERNI).findByName("crm-checker").orElseThrow().id());
        ledgers.get(ERNI).record(EVERYTHING.stream().filter(key -> !key.equals("agent:crm-checker"))
                .map(key -> InstalledSeed.of(key.substring(0, key.indexOf(':')), key.substring(key.indexOf(':') + 1), null))
                .toList());

        restart();
        use(ERNI);

        assertThat(agentNames(ERNI)).containsExactly("crm-assistant", "crm-checker", "host-helper");
    }

    @Test
    void a_switched_off_extension_installs_nothing_until_it_is_switched_on() {
        decisions.computeIfAbsent(ERNI, n -> new InMemoryExtensionActivationRepository())
                .save(ExtensionActivation.of(CRM, false, UserId.of("admin")));

        use(ERNI);

        assertThat(agentNames(ERNI)).containsExactly("host-helper");
        assertThat(skillStores.getOrDefault(ERNI, new InMemorySkillRepository()).findByName("crm-notes")).isEmpty();
        assertThat(workflowStores.getOrDefault(ERNI, new InMemoryWorkflowDataRepository()).exists("crm-flow")).isFalse();
        assertThat(ledger(ERNI)).containsExactlyInAnyOrder("llm-config:seed-test-llm", "agent:host-helper");

        use(LOCAL);
        assertThat(agentNames(LOCAL)).as("switched off in erni only").contains("crm-assistant");

        List<String> installed = in(ERNI, () -> {
            decisions.get(ERNI).save(ExtensionActivation.of(CRM, true, UserId.of("admin")));
            return seeding.extensionsChanged().orElseThrow().installed();
        });

        assertThat(installed).containsExactlyInAnyOrder(
                "agent:crm-assistant", "agent:crm-checker", "skill:crm-notes", "workflow:crm-flow");
        assertThat(agentNames(ERNI)).containsExactly("crm-assistant", "crm-checker", "host-helper");
    }

    @Test
    void it_runs_once_per_namespace() throws Exception {
        use(ERNI);
        use(ERNI);
        in(ERNI, () -> in(LOCAL, () -> in(ERNI, () -> null)));
        Thread other = Thread.ofVirtual().start(() -> use(ERNI));
        other.join();
        seeding.ensure(ERNI);

        assertThat(runs).as("erni once, local once").hasValue(2);
    }

    @Test
    void a_namespace_that_does_not_exist_is_not_seeded() {
        namespaces = new NamespaceService(new InMemoryNamespaceRepository(), LOCAL);
        restart();

        use(new Namespace("deleted-meanwhile"));
        use(LOCAL);

        assertThat(agentStores).doesNotContainKey(new Namespace("deleted-meanwhile"));
        assertThat(agentNames(LOCAL)).contains("host-helper");
    }
}
