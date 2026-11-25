package ai.mindconnect.agent.management;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ai.mindconnect.agent.scope.Namespace;
import ai.mindconnect.agent.scope.ScopeSupplier;
import ai.mindconnect.agent.loop.domain.ContentPart;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.management.api.ManagedMessage;
import ai.mindconnect.agent.management.api.ManagedSession;
import ai.mindconnect.agent.management.adapter.InMemoryAgentRepository;
import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.agent.management.api.SessionAgent;
import ai.mindconnect.agent.management.api.SessionAttachment;
import ai.mindconnect.agent.management.api.SessionDirectories;
import ai.mindconnect.agent.management.port.out.AgentRepository;
import java.util.Optional;
import ai.mindconnect.agent.management.api.SessionDirectoryPolicy;
import ai.mindconnect.agent.management.api.SessionManagement;
import ai.mindconnect.agent.runtime.api.domain.TurnStatus;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.domain.Turn;
import ai.mindconnect.agent.loop.domain.TurnStreamEvent;
import ai.mindconnect.agent.runtime.api.domain.SessionStreamEvent;
import ai.mindconnect.agent.runtime.api.domain.SessionEvent;
import ai.mindconnect.agent.management.api.SessionLifecycleEvent;
import ai.mindconnect.agent.runtime.api.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.domain.Session;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.api.port.out.SessionStore;
import ai.mindconnect.agent.runtime.store.InMemorySessionRepository;
import ai.mindconnect.agent.runtime.store.RepositorySessionStore;
import ai.mindconnect.agent.runtime.store.SessionRepository;
import ai.mindconnect.message.adapter.memory.InMemoryMessageRepository;
import ai.mindconnect.message.port.out.MessageRepository;

/** The management API over the same repositories the runtime's session store writes to. */
class RepositorySessionManagementTest {

    private static final UserId ADA = UserId.of("ada");
    private static final AgentDefinition HELPER = new AgentDefinition("helper", "Helps.", "You help.",
            ModelSettings.NONE, List.of(), 5, Map.of());

    private final SessionRepository sessions = new InMemorySessionRepository(ScopeSupplier.fixed(new Namespace("test")));
    private final MessageRepository messages = new InMemoryMessageRepository(ScopeSupplier.fixed(new Namespace("test")));
    /** What the runtime does: the sessions and messages the management API is to find. */
    private final SessionStore runtime = new RepositorySessionStore(sessions, messages);
    private final AgentRepository agents = new InMemoryAgentRepository(ScopeSupplier.fixed(new Namespace("test")));
    /** The sessions a cleanup was told are gone. */
    private final List<ManagedSession> cleanedUp = new java.util.ArrayList<>();
    private final SessionManagement management = new RepositorySessionManagement(sessions, messages, agents,
            SessionDirectoryPolicy.unrestricted(), List.of(cleanedUp::add),
                new ai.mindconnect.agent.management.SessionLifecycle(sessions, session -> java.util.Optional.empty()));

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
                ManagedSession.WORKING_DIRECTORY, "/srv/owls",
                ManagedSession.ADDITIONAL_DIRECTORIES, List.of("/srv/shared")));

        assertThat(management.find(SessionId.of("plain"))).contains(new ManagedSession(SessionId.of("plain"), ADA,
                null, new SessionAgent.Own(HELPER, null), null, ManagedSession.CHAT, ManagedSession.Status.IDLE, SessionDirectories.NONE,
                Instant.parse("2026-11-01T00:00:00Z"), Map.of()));
        assertThat(management.find(SessionId.of("set-up"))).contains(new ManagedSession(SessionId.of("set-up"), ADA,
                null, new SessionAgent.Own(HELPER, null), "Owls", "research", ManagedSession.Status.IDLE,
                new SessionDirectories("/srv/owls", List.of("/srv/shared")), Instant.parse("2026-11-02T00:00:00Z"),
                Map.of(ManagedSession.TITLE, "Owls", ManagedSession.TYPE, "research",
                        ManagedSession.WORKING_DIRECTORY, "/srv/owls",
                        ManagedSession.ADDITIONAL_DIRECTORIES, List.of("/srv/shared"))));
    }

    @Test
    void what_is_changed_here_is_what_the_runtime_finds() {
        SessionId id = open("s1", "ada", null, "2026-11-01T00:00:00Z", Map.of()).id();
        AgentDefinition other = new AgentDefinition("other", "Else.", "You do else.", ModelSettings.NONE,
                List.of(), 5, Map.of());

        management.rename(id, "Owls");
        management.changeDirectories(id, new SessionDirectories("/srv/owls", List.of()));
        management.setMetadata(id, "chat.pinned", true);
        ManagedSession changed = management.changeAgent(id, other);

        assertThat(changed.title()).isEqualTo("Owls");
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
    void a_session_is_opened_on_an_agent_and_works_where_it_is_told_or_where_the_policy_puts_it() {
        ManagedAgent owl = new RepositoryAgentManagement(agents).create(ManagedAgent.of(new AgentDefinition("owl",
                "Owls.", "You know owls.", ModelSettings.NONE, List.of(), 5, Map.of())));
        SessionDirectoryPolicy homes = new SessionDirectoryPolicy() {
            @Override
            public String admit(UserId user, String directory) {
                if (directory.startsWith("/etc")) {
                    throw new IllegalArgumentException(directory + " is not for users");
                }
                return directory;
            }

            @Override
            public Optional<String> ownDirectory(ManagedSession session) {
                return Optional.empty();
            }

            @Override
            public Optional<String> assign(UserId user, SessionId session) {
                return Optional.of("/home/" + user.value() + "/" + session.value());
            }
        };
        SessionManagement placing = new RepositorySessionManagement(sessions, messages, agents,
            homes, List.of(cleanedUp::add),
                new ai.mindconnect.agent.management.SessionLifecycle(sessions, session -> java.util.Optional.empty()));

        ManagedSession referring = placing.open(ADA, null, new SessionAgent.Ref(owl.id()), null, SessionDirectories.NONE);
        assertThat(referring.agent()).isEqualTo(new SessionAgent.Ref(owl.id()));
        assertThat(referring.type()).isEqualTo(ManagedSession.CHAT);
        assertThat(referring.directories().working()).isEqualTo("/home/ada/" + referring.id().value());
        // The runtime finds it, with the agent's definition to run on.
        assertThat(runtime.find(referring.id()).orElseThrow().agent()).isEqualTo(owl.definition());
        assertThat(placing.list(ADA)).extracting(ManagedSession::id).containsExactly(referring.id());

        ManagedSession ofItsOwn = placing.open(ADA, null, new SessionAgent.Own(HELPER, null), "research",
                new SessionDirectories("/srv/owls", List.of("/srv/mice")));
        assertThat(ofItsOwn.agent()).isEqualTo(new SessionAgent.Own(HELPER, null));
        assertThat(ofItsOwn.type()).isEqualTo("research");
        assertThat(ofItsOwn.directories()).isEqualTo(new SessionDirectories("/srv/owls", List.of("/srv/mice")));

        // Under another session: it may work where that one works, and it is listed under it.
        ManagedSession under = placing.open(ADA, referring.id(), new SessionAgent.Own(HELPER, null), null,
                new SessionDirectories(referring.directories().working(), List.of()));
        assertThat(under.parentId()).isEqualTo(referring.id());
        assertThat(placing.children(referring.id())).extracting(ManagedSession::id).containsExactly(under.id());

        assertThatThrownBy(() -> placing.open(ADA, null, new SessionAgent.Ref(AgentId.of("unknown")), null,
                SessionDirectories.NONE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> placing.open(ADA, null, new SessionAgent.Own(HELPER, null), null,
                new SessionDirectories("/etc", List.of()))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_session_holds_one_file_of_a_name_and_gives_it_up_again() {
        SessionId id = open("s1", "ada", null, "2026-11-01T00:00:00Z", Map.of()).id();
        SessionAttachment owl = new SessionAttachment("f-1", "owl.png", "image/png", 3, null);
        SessionAttachment notes = new SessionAttachment("f-2", "notes.docx", null, 9, "/srv/ada/notes.docx");

        management.attach(id, owl);
        assertThat(management.attach(id, notes).attachments()).containsExactly(owl, notes);
        SessionAttachment larger = new SessionAttachment("f-3", "owl.png", "image/png", 30, null);
        assertThat(management.attach(id, larger).attachments()).containsExactly(notes, larger);
        // The runtime finds them where it reads a session's attachments.
        assertThat(runtime.find(id).orElseThrow().metadata().get(ManagedSession.ATTACHMENTS))
                .isEqualTo(List.of(Map.of("id", "f-2", "name", "notes.docx", "sizeBytes", 9L,
                                "path", "/srv/ada/notes.docx"),
                        Map.of("id", "f-3", "name", "owl.png", "mediaType", "image/png", "sizeBytes", 30L)));

        assertThat(management.detach(id, "notes.docx").attachments()).containsExactly(larger);
        assertThat(management.detach(id, "unknown.txt").attachments()).containsExactly(larger);
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

            @Override
            public Optional<String> assign(UserId user, SessionId session) {
                return Optional.of("/home/" + user.value() + "/" + session.value());
            }
        };
        SessionManagement guarded = new RepositorySessionManagement(sessions, messages, agents,
            underSrv, List.of(cleanedUp::add),
                new ai.mindconnect.agent.management.SessionLifecycle(sessions, session -> java.util.Optional.empty()));
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
        // Whoever keeps something per session hears of each one that went, the one under it first.
        assertThat(cleanedUp).extracting(session -> session.id().value()).containsExactly("child", "top");
        assertThat(messages.findByConversation(ai.mindconnect.message.domain.ConversationId.of("child"),
                new ai.mindconnect.common.PageRequest(0, 10))).isEmpty();
        assertThat(runtime.messages(other)).hasSize(1);
    }

    @Test
    void a_sessions_status_is_read_from_its_turn_and_not_kept() {
        SessionId id = open("s1", "ada", null, "2026-11-01T00:00:00Z", Map.of()).id();
        java.util.concurrent.atomic.AtomicReference<TurnStatus> turn = new java.util.concurrent.atomic.AtomicReference<>();
        SessionManagement reading = new RepositorySessionManagement(sessions, messages, agents,
                SessionDirectoryPolicy.unrestricted(), List.of(),
                new SessionLifecycle(sessions, session -> java.util.Optional.ofNullable(turn.get())
                        .map(status -> new Turn(TurnId.of("t1"), null, session, HELPER, status, List.of(), null, null))));

        assertThat(reading.find(id).orElseThrow().status()).isEqualTo(ManagedSession.Status.IDLE);
        turn.set(TurnStatus.RUNNING);
        assertThat(reading.find(id).orElseThrow().status()).isEqualTo(ManagedSession.Status.BUSY);
        turn.set(TurnStatus.INTERRUPTED);
        assertThat(reading.find(id).orElseThrow().status()).isEqualTo(ManagedSession.Status.WAITING);
    }

    @Test
    void a_user_hears_what_changes_about_their_sessions_and_nobody_elses() throws InterruptedException {
        SessionLifecycle lifecycle = new SessionLifecycle(sessions, session -> java.util.Optional.empty());
        SessionManagement telling = new RepositorySessionManagement(sessions, messages, agents,
                SessionDirectoryPolicy.unrestricted(), List.of(), lifecycle);
        List<SessionLifecycleEvent> ada = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<SessionLifecycleEvent> bob = new java.util.concurrent.CopyOnWriteArrayList<>();
        telling.lifecycle(ADA, ada::add);
        telling.lifecycle(UserId.of("bob"), bob::add);

        SessionId id = telling.open(ADA, null, new SessionAgent.Own(HELPER, null), null, SessionDirectories.NONE).id();
        SessionId below = open("below", "ada", id.value(), "2026-11-01T00:00:00Z", Map.of()).id();
        telling.rename(id, "Owls");
        // What a turn does reaches the list as the status of the session the user sees.
        lifecycle.onEvent(SessionEvent.of(id, TurnId.of("t1"), null,
                new TurnStreamEvent.AskingLlm()));
        lifecycle.onEvent(SessionEvent.of(id, TurnId.of("t1"), null, new TurnStreamEvent.TextDelta("x")));
        lifecycle.onEvent(SessionEvent.of(below, TurnId.of("t2"), TurnId.of("t1"),
                new SessionStreamEvent.Waiting()));
        lifecycle.onEvent(SessionEvent.of(below, TurnId.of("t2"), TurnId.of("t1"),
                new SessionStreamEvent.Done(TurnStatus.COMPLETED, "", null)));
        lifecycle.onEvent(SessionEvent.of(id, TurnId.of("t1"), null,
                new SessionStreamEvent.Done(TurnStatus.COMPLETED, "Done.", null)));
        lifecycle.onEvent(new SessionEvent("session.title", id, null, null, Map.of("title", "Owls at night")));
        telling.delete(id);

        for (int i = 0; i < 200 && ada.size() < 7; i++) {
            Thread.sleep(10);
        }
        assertThat(ada).containsExactly(
                new SessionLifecycleEvent.Opened(id, null, ManagedSession.CHAT),
                new SessionLifecycleEvent.Renamed(id, "Owls"),
                new SessionLifecycleEvent.StatusChanged(id, ManagedSession.Status.BUSY),
                new SessionLifecycleEvent.StatusChanged(id, ManagedSession.Status.WAITING),
                new SessionLifecycleEvent.StatusChanged(id, ManagedSession.Status.IDLE),
                new SessionLifecycleEvent.Renamed(id, "Owls at night"),
                new SessionLifecycleEvent.Deleted(id));
        assertThat(bob).isEmpty();
    }
}
