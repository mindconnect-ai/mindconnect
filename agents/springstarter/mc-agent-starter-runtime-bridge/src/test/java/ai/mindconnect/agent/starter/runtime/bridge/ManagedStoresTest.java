package ai.mindconnect.agent.starter.runtime.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.management.RepositoryAgentManagement;
import ai.mindconnect.agent.management.adapter.InMemoryAgentRepository;
import ai.mindconnect.agent.management.api.AgentManagement;
import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.management.api.ManagedSession;
import ai.mindconnect.agent.management.port.out.AgentRepository;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.port.out.NamespaceProvider;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.domain.AgentDefinitionStatus;
import ai.mindconnect.agent.runtime.domain.AgentSession;
import ai.mindconnect.agent.runtime.domain.SessionStatus;
import ai.mindconnect.agent.runtime.domain.session.SessionAgentRef;
import ai.mindconnect.agent.runtime.engine.adapter.store.InMemorySessionRepository;
import ai.mindconnect.agent.runtime.engine.port.out.SessionRepository;
import ai.mindconnect.agent.runtime.memory.domain.AutoCompactConfig;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
import ai.mindconnect.agent.runtime.port.out.AgentSessionRepository;
import ai.mindconnect.agent.tool.AgentTool;
import ai.mindconnect.agent.tool.AgentToolId;
import ai.mindconnect.message.domain.ConversationId;

/** The earlier runtime's two repositories, kept in the stores of the new one. */
class ManagedStoresTest {

    private static final NamespaceProvider NAMESPACES = NamespaceProvider.fixed("test");
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

    private static final AgentDefinition LIBRARIAN = new AgentDefinition(AgentId.random(), "librarian",
            "Finds books.", "assistants", "book", "You find books.", "Which book?", "small", 6,
            AutoCompactConfig.DEFAULT, AgentDefinitionStatus.ACTIVE,
            List.of(new AgentTool(AgentToolId.random(), "search_catalog", "Searches our catalog.",
                            Map.of("branch", "main"), true, false, false, null),
                    new AgentTool(AgentToolId.random(), "switched_off", null, Map.of(), false, false, false, null)),
            List.of("proof-reader"), List.of("archivist"), true, null, Instant.EPOCH, Instant.EPOCH);

    private final AgentRepository managedAgents = new InMemoryAgentRepository(NAMESPACES);
    private final AgentDefinitionRepository earlierAgents = mock(AgentDefinitionRepository.class);
    private final AgentDefinitionRepository agents = new ManagedAgentDefinitionRepository(
            () -> managedAgents, earlierAgents, new AgentDefinitionMapper(), JSON, () -> NAMESPACES);

    private final SessionRepository runtimeSessions = new InMemorySessionRepository(NAMESPACES);
    private final AgentSessionRepository earlierSessions = mock(AgentSessionRepository.class);
    private final AgentSessionRepository sessions = new ManagedAgentSessionRepository(
            () -> runtimeSessions, earlierSessions, JSON, () -> NAMESPACES,
            written -> ManagedStoresFeature.agentOf(written, agents, new AgentDefinitionMapper()));

    private static AgentSession chat(String user) {
        return new AgentSession(SessionId.random(), LIBRARIAN.id(), UserId.of(user), ConversationId.random(), null,
                SessionStatus.ACTIVE, Instant.parse("2026-11-23T10:00:00Z"), null, null, null, null)
                .withWorkingDir("/srv/books");
    }

    @Test
    void an_agent_saved_the_earlier_way_is_a_managed_agent_and_reads_back_whole() {
        agents.save(LIBRARIAN);

        ManagedAgent managed = managedAgents.find(
                ai.mindconnect.agent.management.api.AgentId.of(LIBRARIAN.id().value())).orElseThrow();
        assertThat(managed.definition().name()).isEqualTo("librarian");
        assertThat(managed.definition().tools()).extracting(binding -> binding.name()).containsExactly("search_catalog");
        assertThat(managed.group()).isEqualTo("assistants");
        // What the runtime's definition has no place for comes back too: the tool switched off, the reviewers.
        AgentDefinition read = agents.findById(LIBRARIAN.id()).orElseThrow();
        assertThat(read.tools()).isEqualTo(LIBRARIAN.tools());
        assertThat(read.responseReviewers()).containsExactly("proof-reader");
        assertThat(read.callableAgents()).containsExactly("archivist");
        assertThat(read.memoryConfig()).isEqualTo(AutoCompactConfig.DEFAULT);
        assertThat(read.welcomeMessage()).isEqualTo("Which book?");
        assertThat(agents.findByName("librarian")).isPresent();
    }

    @Test
    void what_the_management_changes_shows_to_whoever_reads_the_earlier_way() {
        agents.save(LIBRARIAN);
        AgentManagement management = new RepositoryAgentManagement(managedAgents);
        ManagedAgent managed = management.findByName("librarian").orElseThrow();

        management.update(managed.id(), managed.version(), agent -> agent.withDefinition(
                agent.definition().withModelSettings(ModelSettings.ofModel("large"))).shownAs("experts", "book", "Hi"));

        AgentDefinition read = agents.findById(LIBRARIAN.id()).orElseThrow();
        assertThat(read.llmConfigName()).isEqualTo("large");
        assertThat(read.group()).isEqualTo("experts");
        assertThat(read.version()).isEqualTo(2L);
        assertThat(read.systemPrompt()).isEqualTo("You find books.");
    }

    @Test
    void the_agents_of_the_earlier_store_are_taken_over_once_and_deleted_from_both() {
        when(earlierAgents.findAll()).thenReturn(List.of(LIBRARIAN));

        assertThat(agents.findAll()).extracting(AgentDefinition::name).containsExactly("librarian");
        assertThat(managedAgents.findAll()).hasSize(1);

        agents.deleteById(LIBRARIAN.id());

        assertThat(managedAgents.findAll()).isEmpty();
        verify(earlierAgents).deleteById(LIBRARIAN.id());
        verify(earlierAgents).findAll();
    }

    @Test
    void a_chats_session_is_the_runtimes_session_and_reads_back_as_it_was_written() {
        AgentSession chat = chat("ada");

        sessions.create(chat);

        var runtime = runtimeSessions.find(
                ai.mindconnect.agent.runtime.api.domain.SessionId.of(chat.id().value())).orElseThrow();
        assertThat(runtime.userId().value()).isEqualTo("ada");
        assertThat(runtime.metadata()).containsEntry(ManagedSession.WORKING_DIRECTORY, "/srv/books")
                .containsEntry("conversationId", chat.conversationId().value())
                .doesNotContainKey(ManagedSession.TITLE);
        assertThat(sessions.findById(chat.id())).contains(chat);
        assertThat(sessions.findByUser(UserId.of("ada"))).containsExactly(chat);
        assertThat(sessions.findByAgent(LIBRARIAN.id(), UserId.of("ada"))).containsExactly(chat);
    }

    @Test
    void a_chat_refers_to_its_agent_until_it_chooses_something_of_its_own() {
        agents.save(LIBRARIAN);
        AgentSession chat = sessions.create(chat("ada"));
        ai.mindconnect.agent.runtime.api.domain.SessionId id =
                ai.mindconnect.agent.runtime.api.domain.SessionId.of(chat.id().value());

        assertThat(runtimeSessions.find(id).orElseThrow().metadata())
                .containsEntry(ManagedSession.AGENT, ManagedSession.REF)
                .containsEntry(ManagedSession.AGENT_ID, LIBRARIAN.id().value());
        assertThat(runtimeSessions.find(id).orElseThrow().agent().instructions()).isEqualTo("You find books.");

        // A prompt of the chat's own: the definition is the session's from here on.
        sessions.update(chat.id(), session -> session.withSessionAgent(new SessionAgentRef(LIBRARIAN.id(), true,
                "librarian", null, null, "You find maps.")));

        assertThat(runtimeSessions.find(id).orElseThrow().metadata())
                .containsEntry(ManagedSession.AGENT, ManagedSession.OWN)
                .containsEntry(ManagedSession.AGENT_ID, LIBRARIAN.id().value());
        assertThat(runtimeSessions.find(id).orElseThrow().agent().instructions()).isEqualTo("You find maps.");
    }

    @Test
    void what_the_runtime_keeps_in_the_session_shows_to_the_chat_and_survives_the_chats_changes() {
        AgentSession chat = chat("ada");
        sessions.create(chat);
        var id = ai.mindconnect.agent.runtime.api.domain.SessionId.of(chat.id().value());
        // The runtime found a title, remembered an approval and noted its turn.
        runtimeSessions.update(id, session -> session.withMetadata(ManagedSession.TITLE, "Owl books")
                .withMetadata("approvedTools", List.of("bash")).withMetadata("lastTurn", "t1"));

        AgentSession read = sessions.findById(chat.id()).orElseThrow();
        assertThat(read.title()).isEqualTo("Owl books");
        assertThat(read.approvedTools()).containsExactly("bash");

        sessions.update(chat.id(), session -> session.withWorkingDir("/srv/owls"));

        var runtime = runtimeSessions.find(id).orElseThrow();
        assertThat(runtime.metadata()).containsEntry(ManagedSession.WORKING_DIRECTORY, "/srv/owls")
                .containsEntry(ManagedSession.TITLE, "Owl books").containsEntry("lastTurn", "t1");
    }

    @Test
    void a_session_the_runtime_already_knows_is_completed_from_the_earlier_store_not_replaced() {
        AgentSession kept = chat("ada");
        when(earlierSessions.findById(kept.id())).thenReturn(Optional.of(kept));
        var id = ai.mindconnect.agent.runtime.api.domain.SessionId.of(kept.id().value());
        runtimeSessions.create(new ai.mindconnect.agent.runtime.api.domain.Session(id,
                ai.mindconnect.agent.runtime.api.domain.UserId.of("ada"), null, null,
                Map.of("lastTurn", "t1", ManagedSession.TITLE, "Owl books", "approvedTools", List.of("bash")),
                Instant.EPOCH));

        AgentSession read = sessions.findById(kept.id()).orElseThrow();

        // What the runtime had kept wins over what the earlier store says of the same thing.
        assertThat(read.title()).isEqualTo("Owl books");
        assertThat(read.approvedTools()).containsExactly("bash");

        assertThat(read.agentDefinitionId()).isEqualTo(LIBRARIAN.id());
        assertThat(read.workingDir()).isEqualTo("/srv/books");
        assertThat(runtimeSessions.find(id).orElseThrow().metadata()).containsEntry("lastTurn", "t1");
    }

    @Test
    void a_session_the_runtime_opened_itself_reads_as_a_session_under_its_parent() {
        AgentSession parent = chat("ada");
        sessions.create(parent);
        var child = ai.mindconnect.agent.runtime.api.domain.SessionId.of("child-of-" + parent.id().value());
        runtimeSessions.create(new ai.mindconnect.agent.runtime.api.domain.Session(child,
                ai.mindconnect.agent.runtime.api.domain.UserId.of("ada"),
                ai.mindconnect.agent.runtime.api.domain.SessionId.of(parent.id().value()), null, Map.of(),
                Instant.parse("2026-11-23T11:00:00Z")));

        List<AgentSession> children = sessions.findByParentSession(parent.id());

        assertThat(children).hasSize(1);
        assertThat(children.get(0).parentSessionId()).isEqualTo(parent.id());
        assertThat(children.get(0).userId()).isEqualTo(UserId.of("ada"));
    }

    @Test
    void a_session_of_the_earlier_store_is_taken_over_when_it_is_asked_for() {
        AgentSession kept = chat("ada");
        when(earlierSessions.findById(kept.id())).thenReturn(Optional.of(kept));
        when(earlierSessions.findByUser(UserId.of("ada"))).thenReturn(List.of(kept));

        assertThat(sessions.findById(kept.id())).contains(kept);
        assertThat(sessions.findByUser(UserId.of("ada"))).containsExactly(kept);
        assertThat(runtimeSessions.findByUser(ai.mindconnect.agent.runtime.api.domain.UserId.of("ada"))).hasSize(1);

        sessions.deleteById(kept.id());

        verify(earlierSessions).deleteById(kept.id());
        assertThat(runtimeSessions.findByUser(ai.mindconnect.agent.runtime.api.domain.UserId.of("ada"))).isEmpty();
    }
}
