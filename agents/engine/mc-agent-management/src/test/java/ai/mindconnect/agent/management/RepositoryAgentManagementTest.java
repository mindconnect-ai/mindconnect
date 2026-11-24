package ai.mindconnect.agent.management;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import ai.mindconnect.agent.management.adapter.FileAgentRepository;
import ai.mindconnect.agent.management.adapter.InMemoryAgentRepository;
import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.management.api.AgentManagement;
import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.management.port.out.AgentRepository;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.domain.ToolBinding;
import ai.mindconnect.agent.runtime.api.port.out.NamespaceProvider;

/** The same management over both kinds of repository. */
class RepositoryAgentManagementTest {

    private static final NamespaceProvider NAMESPACES = NamespaceProvider.fixed("test");

    @TempDir
    static Path directory;

    /** A fresh repository of each kind per test: the file one in a folder of its own. */
    static Stream<AgentRepository> repositories() {
        return Stream.of(
                new InMemoryAgentRepository(NAMESPACES),
                new FileAgentRepository(directory.resolve(String.valueOf(System.nanoTime())),
                        new ObjectMapper().registerModule(new JavaTimeModule()), NAMESPACES));
    }

    private static AgentDefinition definition(String name) {
        return new AgentDefinition(name, "Looks things up.", "You research.", ModelSettings.ofModel("small"),
                List.of(new ToolBinding("web_search", false, Map.of("needsApproval", true))), 6,
                Map.of("subAgents", List.of("fact-checker")));
    }

    @ParameterizedTest
    @MethodSource("repositories")
    void a_new_agent_is_given_its_id_and_comes_back_as_it_was_stored(AgentRepository repository) {
        AgentManagement agents = new RepositoryAgentManagement(repository);

        ManagedAgent created = agents.create(
                ManagedAgent.of(definition("researcher")).shownAs("assistants", "telescope", "What shall I look up?"));

        assertThat(created.id()).isNotNull();
        assertThat(created.version()).isEqualTo(1);
        assertThat(created.createdAt()).isNotNull();
        assertThat(agents.find(created.id())).contains(created);
        assertThat(agents.findByName("researcher")).contains(created);
        assertThat(created.definition()).as("as given, and saying whose it is")
                .isEqualTo(identified(definition("researcher"), created.id()));
        assertThatThrownBy(() -> agents.create(ManagedAgent.of(definition("researcher"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @MethodSource("repositories")
    void a_change_made_from_the_stored_version_counts_and_one_made_from_an_older_is_refused(
            AgentRepository repository) {
        AgentManagement agents = new RepositoryAgentManagement(repository);
        ManagedAgent created = agents.create(ManagedAgent.of(definition("researcher")));
        agents.create(ManagedAgent.of(definition("writer")));

        ManagedAgent renamed = agents.update(created.id(), 1, agent -> agent.withDefinition(definition("scout")));

        assertThat(renamed.id()).isEqualTo(created.id());
        assertThat(renamed.version()).isEqualTo(2);
        assertThat(renamed.createdAt()).isEqualTo(created.createdAt());
        assertThat(agents.findByName("researcher")).isEmpty();
        assertThatThrownBy(() -> agents.update(created.id(), 1, agent -> agent))
                .isInstanceOf(ConcurrentModificationException.class);
        assertThatThrownBy(() -> agents.update(created.id(), 2, agent -> agent.withDefinition(definition("writer"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(agents.find(created.id()).orElseThrow().version()).isEqualTo(2);
        assertThatThrownBy(() -> agents.update(AgentId.of("unknown"), 1, agent -> agent))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @MethodSource("repositories")
    void agents_are_listed_by_name_copied_under_a_free_name_and_deleted(AgentRepository repository) {
        AgentManagement agents = new RepositoryAgentManagement(repository);
        ManagedAgent writer = agents.create(ManagedAgent.of(definition("writer")));
        agents.create(ManagedAgent.of(definition("researcher")));

        ManagedAgent first = agents.copy(writer.id());
        ManagedAgent second = agents.copy(writer.id());

        assertThat(first.definition().name()).isEqualTo("writer-copy");
        assertThat(second.definition().name()).isEqualTo("writer-copy-2");
        assertThat(first.id()).isNotEqualTo(writer.id());
        assertThat(agents.list()).extracting(agent -> agent.definition().name())
                .containsExactly("researcher", "writer", "writer-copy", "writer-copy-2");

        agents.delete(writer.id());
        agents.delete(AgentId.of("never-there"));

        assertThat(agents.find(writer.id())).isEmpty();
        assertThat(agents.list()).hasSize(3);
    }

    @ParameterizedTest
    @MethodSource("repositories")
    void the_runtime_finds_an_active_agent_by_name_and_no_other(AgentRepository repository) {
        AgentManagement agents = new RepositoryAgentManagement(repository);
        agents.create(ManagedAgent.of(definition("researcher")));
        agents.create(ManagedAgent.of(definition("draft")).withStatus(ManagedAgent.Status.DRAFT));
        ManagedAgentResolver resolver = new ManagedAgentResolver(agents);

        assertThat(resolver.resolve("researcher", null))
                .contains(identified(definition("researcher"), agents.findByName("researcher").orElseThrow().id()));
        assertThat(resolver.resolve("draft", null)).isEmpty();
        assertThat(resolver.resolve("nobody", null)).isEmpty();
    }

    /** The definition as it is stored: with its agent's id in its metadata. */
    private static AgentDefinition identified(AgentDefinition definition, AgentId id) {
        Map<String, Object> metadata = new java.util.HashMap<>(definition.metadata());
        metadata.put(ManagedAgent.AGENT_ID, id.value());
        return new AgentDefinition(definition.name(), definition.description(), definition.instructions(),
                definition.modelSettings(), definition.tools(), definition.maxRounds(), metadata);
    }
}
