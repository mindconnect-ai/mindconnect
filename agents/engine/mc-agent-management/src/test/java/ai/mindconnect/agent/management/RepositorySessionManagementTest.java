package ai.mindconnect.agent.management;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.loop.domain.ContentPart;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.management.api.ManagedMessage;
import ai.mindconnect.agent.management.api.ManagedSession;
import ai.mindconnect.agent.management.adapter.InMemoryAgentRepository;
import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.management.api.SessionAgent;
import ai.mindconnect.agent.management.api.SessionDirectories;
import ai.mindconnect.agent.management.port.out.AgentRepository;
import java.util.Optional;
import ai.mindconnect.agent.management.api.SessionDirectoryPolicy;
import ai.mindconnect.agent.management.api.SessionManagement;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.runtime.api.domain.SessionId;
import ai.mindconnect.agent.runtime.api.domain.UserId;
import ai.mindconnect.agent.runtime.api.port.out.NamespaceProvider;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.runtime.engine.adapter.store.InMemorySessionRepository;
import ai.mindconnect.agent.runtime.engine.adapter.store.RepositorySessionStore;
import ai.mindconnect.agent.runtime.engine.port.out.SessionRepository;
import ai.mindconnect.message.adapter.memory.InMemoryMessageRepository;
import ai.mindconnect.message.port.out.MessageRepository;

/** The management API over the same repositories the runtime's session store writes to. */
class RepositorySessionManagementTest {

    private static final UserId ADA = UserId.of("ada");
    private static final AgentDefinition HELPER = new AgentDefinition("helper", "Helps.", "You help.",
            ModelSettings.NONE, List.of(), 5, Map.of());

    private final SessionRepository sessions = new InMemorySessionRepository(NamespaceProvider.fixed("test"));
    private final MessageRepository messages = new InMemoryMessageRepository(ScopeSupplier.fixed(new Namespace("test")));
    /** What the runtime does: the sessions and messages the management API is to find. */
    private final SessionStore runtime = new RepositorySessionStore(sessions, messages);
    private final AgentRepository agents = new InMemoryAgentRepository(NamespaceProvider.fixed("test"));
    private final SessionManagement management = new RepositorySessionManagement(sessions, messages, agents,
            SessionDirectoryPolicy.unrestricted());

    private Session open(String id, String user, String parent, String createdAt, Map<String, Object> metadata) {
        Session session = new Session(SessionId.of(id), UserId.of(user), parent == null ? null : SessionId.of(parent),
                HELPER, metadata, Instant.parse(createdAt));
        runtime.create(session);
        return session;
    }

    @Test
    void a_users_sessions_are_listed_newest_first_and_without_those_under_another() {
        open("old", "ada", null, "2026-11-01T00:00:00Z", Map.of());
        open("new", "ada", null, "2026-11-03T00:00:00Z", Map.of());
        open("child", "ada", "new", "2026-11-04T00:00:00Z", Map.of());
        open("eves", "eve", null, "2026-11-05T00:00:00Z", Map.of());

        assertThat(management.list(ADA)).extracting(session -> session.id().value()).containsExactly("new", "old");
        assertThat(management.children(SessionId.of("new"))).extracting(session -> session.id().value())
                .containsExactly("child");
    }

    @Test
    void a_session_shows_what_its_metadata_holds_and_what_it_lacks_reads_as_a_plain_chat() {
        open("plain", "ada", null, "2026-11-01T00:00:00Z", Map.of());
        open("set-up", "ada", null, "2026-11-02T00:00:00Z", Map.of(
                ManagedSession.TITLE, "Owls", ManagedSession.TYPE, "research",
                ManagedSession.STATUS, "COMPLETED", ManagedSession.WORKING_DIRECTORY, "/srv/owls",
                ManagedSession.ADDITIONAL_DIRECTORIES, List.of("/srv/shared")));

        assertThat(management.find(SessionId.of("plain"))).contains(new ManagedSession(SessionId.of("plain"), ADA,
                null, new SessionAgent.Own(HELPER, null), null, ManagedSession.CHAT, ManagedSession.Status.ACTIVE, SessionDirectories.NONE,
                Instant.parse("2026-11-01T00:00:00Z"), Map.of()));
        assertThat(management.find(SessionId.of("set-up"))).contains(new ManagedSession(SessionId.of("set-up"), ADA,
                null, new SessionAgent.Own(HELPER, null), "Owls", "research", ManagedSession.Status.COMPLETED,
                new SessionDirectories("/srv/owls", List.of("/srv/shared")), Instant.parse("2026-11-02T00:00:00Z"),
                Map.of(ManagedSession.TITLE, "Owls", ManagedSession.TYPE, "research",
                        ManagedSession.STATUS, "COMPLETED", ManagedSession.WORKING_DIRECTORY, "/srv/owls",
                        ManagedSession.ADDITIONAL_DIRECTORIES, List.of("/srv/shared"))));
    }

    @Test
    void what_is_changed_here_is_what_the_runtime_finds() {
        SessionId id = open("s1", "ada", null, "2026-11-01T00:00:00Z", Map.of()).id();
        AgentDefinition other = new AgentDefinition("other", "Else.", "You do else.", ModelSettings.NONE,
                List.of(), 5, Map.of());

        management.rename(id, "Owls");
        management.changeStatus(id, ManagedSession.Status.CANCELLED);
        management.changeDirectories(id, new SessionDirectories("/srv/owls", List.of()));
        management.setMetadata(id, "chat.pinned", true);
        ManagedSession changed = management.changeAgent(id, other);

        assertThat(changed.title()).isEqualTo("Owls");
        assertThat(changed.status()).isEqualTo(ManagedSession.Status.CANCELLED);
        assertThat(changed.agent()).isEqualTo(new SessionAgent.Own(other, null));
        assertThat(changed.metadata()).containsEntry("chat.pinned", true);
        Session found = runtime.find(id).orElseThrow();
        assertThat(found.agent()).isEqualTo(other);
        assertThat(found.metadata()).containsEntry(ManagedSession.TITLE, "Owls")
                .containsEntry(ManagedSession.WORKING_DIRECTORY, "/srv/owls");
        // Taking the working directory away leaves no entry behind.
        assertThat(management.changeDirectories(id, SessionDirectories.NONE).directories())
                .isEqualTo(SessionDirectories.NONE);
        assertThat(runtime.find(id).orElseThrow().metadata()).doesNotContainKey(ManagedSession.WORKING_DIRECTORY);
        assertThatThrownBy(() -> management.rename(SessionId.of("unknown"), "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_session_follows_the_managed_agent_it_refers_to_until_it_is_given_a_definition_of_its_own() {
        SessionId id = open("s1", "ada", null, "2026-11-01T00:00:00Z", Map.of()).id();
        ManagedAgent owl = new RepositoryAgentManagement(agents).create(ManagedAgent.of(new AgentDefinition("owl", "Owls.", "You know owls.",
                ModelSettings.NONE, List.of(), 5, Map.of())));
        assertThat(management.find(id).orElseThrow().agent()).isEqualTo(new SessionAgent.Own(HELPER, null));

        assertThat(management.useAgent(id, owl.id()).agent()).isEqualTo(new SessionAgent.Ref(owl.id()));
        assertThat(management.agentOf(id)).contains(owl.definition());
        AgentDefinition edited = new AgentDefinition("owl", "Owls.", "You know barn owls.", ModelSettings.NONE,
                List.of(), 5, Map.of());
        agents.update(owl.id(), agent -> agent.withDefinition(edited));
        assertThat(management.agentOf(id)).as("the agent as it stands now").contains(edited);

        // A definition of its own: the managed agent is where it came from, and no longer followed.
        assertThat(management.changeAgent(id, HELPER).agent()).isEqualTo(new SessionAgent.Own(HELPER, owl.id()));
        assertThat(management.agentOf(id)).contains(HELPER);

        // An agent that is gone leaves the session what it last ran on.
        management.useAgent(id, owl.id());
        agents.delete(owl.id());
        assertThat(management.agentOf(id)).contains(edited);
        assertThatThrownBy(() -> management.useAgent(id, owl.id())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_new_directory_has_to_pass_the_policy_and_the_sessions_own_is_kept() {
        SessionDirectoryPolicy underSrv = new SessionDirectoryPolicy() {
            @Override
            public String admit(UserId user, String directory) {
                if (!directory.startsWith("/srv/")) {
                    throw new IllegalArgumentException(directory + " is outside /srv");
                }
                return directory;
            }

            @Override
            public Optional<String> ownDirectory(ManagedSession session) {
                return Optional.of("/home/" + session.userId().value() + "/" + session.id().value());
            }
        };
        SessionManagement guarded = new RepositorySessionManagement(sessions, messages, agents, underSrv);
        SessionId id = open("s1", "ada", null, "2026-11-01T00:00:00Z", Map.of()).id();

        assertThat(guarded.changeDirectories(id, new SessionDirectories("/srv/owls", List.of("/srv/mice")))
                .directories()).isEqualTo(new SessionDirectories("/srv/owls", List.of("/srv/mice", "/home/ada/s1")));
        assertThatThrownBy(() -> guarded.changeDirectories(id, new SessionDirectories("/etc", List.of())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("outside /srv");
        // Its own directory lies outside the root and still passes: as a held one, and as where it works.
        assertThat(guarded.changeDirectories(id, new SessionDirectories("/home/ada/s1", List.of())).directories())
                .isEqualTo(new SessionDirectories("/home/ada/s1", List.of()));
        assertThat(guarded.changeDirectories(id, SessionDirectories.NONE).directories())
                .isEqualTo(new SessionDirectories(null, List.of("/home/ada/s1")));
    }

    @Test
    void messages_are_read_in_order_and_deleted_from_one_on() {
        SessionId id = open("s1", "ada", null, "2026-11-01T00:00:00Z", Map.of()).id();
        runtime.append(id, List.of(
                TurnMessage.user(List.of(new ContentPart.Text("First question"))),
                TurnMessage.assistant("First answer"),
                TurnMessage.user(List.of(new ContentPart.Text("Second question"))),
                TurnMessage.assistant("Second answer")));

        List<ManagedMessage> said = management.messages(id);
        assertThat(said).extracting(ManagedMessage::sequence).containsExactly(1, 2, 3, 4);
        assertThat(said).extracting(message -> message.message().text())
                .containsExactly("First question", "First answer", "Second question", "Second answer");
        assertThat(said).allSatisfy(message -> assertThat(message.sentAt()).isNotNull());

        management.deleteMessagesFrom(id, 3);

        assertThat(runtime.messages(id)).extracting(TurnMessage::text).containsExactly("First question", "First answer");
    }

    @Test
    void deleting_a_session_takes_what_was_said_in_it_and_the_sessions_under_it() {
        SessionId top = open("top", "ada", null, "2026-11-01T00:00:00Z", Map.of()).id();
        SessionId child = open("child", "ada", "top", "2026-11-02T00:00:00Z", Map.of()).id();
        SessionId other = open("other", "ada", null, "2026-11-03T00:00:00Z", Map.of()).id();
        for (SessionId id : List.of(top, child, other)) {
            runtime.append(id, List.of(TurnMessage.assistant("Said in " + id.value())));
        }

        management.delete(top);
        management.delete(SessionId.of("never-there"));

        assertThat(management.list(ADA)).extracting(session -> session.id().value()).containsExactly("other");
        assertThat(management.find(child)).isEmpty();
        assertThat(messages.findByConversation(ai.mindconnect.message.domain.ConversationId.of("child"),
                new ai.mindconnect.common.PageRequest(0, 10))).isEmpty();
        assertThat(runtime.messages(other)).hasSize(1);
    }
}
