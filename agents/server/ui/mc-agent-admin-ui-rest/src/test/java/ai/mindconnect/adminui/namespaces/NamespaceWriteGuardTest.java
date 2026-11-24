package ai.mindconnect.adminui.namespaces;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.namespace.domain.Email;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.Scope;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.management.api.AgentManagement;
import ai.mindconnect.agent.management.api.ManagedAgent;
import ai.mindconnect.namespace.adapter.memory.InMemoryNamespaceRepository;
import ai.mindconnect.namespace.domain.NamespaceRole;
import ai.mindconnect.namespace.service.NamespaceService;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The last question before something is written. The point of asking it here
 * as well as at the request: a write can come from a tool, a task or a
 * controller that nobody remembered to put behind the interceptor.
 */
class NamespaceWriteGuardTest {

    private static final Instant NOW = Instant.parse("2026-09-17T12:00:00Z");
    private static final Namespace ACME = new Namespace("acme");
    private static final UserId DAVID = UserId.of("david");
    private static final UserId ALICE = UserId.of("alice");

    private final NamespaceService namespaces = new NamespaceService(new InMemoryNamespaceRepository(),
            Namespace.DEFAULT, Clock.fixed(NOW, ZoneOffset.UTC), List.of(),
            id -> Optional.of(Email.of(id.value() + "@acme.example")), "acme.example", List.of());

    /** A store that records what reached it, so a refused write is visible as "nothing did". */
    private static final class Recording implements AgentManagement {
        final List<String> saved = new ArrayList<>();
        final List<AgentId> deleted = new ArrayList<>();

        @Override public ManagedAgent create(ManagedAgent agent) {
            saved.add(agent.definition().name());
            return agent;
        }

        @Override public ManagedAgent update(AgentId id, long expectedVersion,
                                             java.util.function.UnaryOperator<ManagedAgent> change) {
            saved.add(id.value());
            return null;
        }

        @Override public ManagedAgent copy(AgentId id) {
            saved.add(id.value());
            return null;
        }

        @Override public void delete(AgentId id) {
            deleted.add(id);
        }

        @Override public Optional<ManagedAgent> find(AgentId id) {
            return Optional.empty();
        }

        @Override public Optional<ManagedAgent> findByName(String name) {
            return Optional.empty();
        }

        @Override public List<ManagedAgent> list() {
            return List.of();
        }
    }

    private final Recording store = new Recording();

    NamespaceWriteGuardTest() {
        namespaces.create("acme", "ACME", DAVID);
        namespaces.invite(ACME, DAVID, Email.of("alice@acme.example"), NamespaceRole.USER);
    }

    private AgentManagement guardedFor(Scope scope) {
        return new GuardedRepositories.ManagedAgents(store,
                new NamespaceWriteGuard(namespaces, ScopeSupplier.fixed(scope)));
    }

    private static ManagedAgent agent() {
        return ManagedAgent.of(new ai.mindconnect.agent.runtime.api.domain.AgentDefinition("helper", "Helps",
                "Be helpful.", ai.mindconnect.agent.runtime.api.domain.ModelSettings.NONE, List.of(), 10,
                java.util.Map.of()));
    }

    @Test
    void anAdminOfTheNamespaceWrites() {
        guardedFor(Scope.of(ACME, DAVID)).create(agent());

        assertThat(store.saved).containsExactly("helper");
    }

    @Test
    void aUserOfTheNamespaceDoesNot_andNothingReachesTheStore() {
        AgentManagement guarded = guardedFor(Scope.of(ACME, ALICE));

        assertThatThrownBy(() -> guarded.create(agent()))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Only an admin of 'acme'");
        assertThatThrownBy(() -> guarded.delete(AgentId.of("a1")))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(store.saved).isEmpty();
        assertThat(store.deleted).isEmpty();
    }

    @Test
    void theAgentsTheRuntimeGivesOutAreGuardedTheSame_aUserReadsAndDoesNotWrite() {
        List<String> reached = new ArrayList<>();
        var managed = new ai.mindconnect.agent.management.api.AgentManagement() {
            @Override public List<ai.mindconnect.agent.management.api.ManagedAgent> list() {
                reached.add("list");
                return List.of();
            }
            @Override public Optional<ai.mindconnect.agent.management.api.ManagedAgent> find(
                    AgentId id) { return Optional.empty(); }
            @Override public Optional<ai.mindconnect.agent.management.api.ManagedAgent> findByName(String name) {
                return Optional.empty();
            }
            @Override public ai.mindconnect.agent.management.api.ManagedAgent create(
                    ai.mindconnect.agent.management.api.ManagedAgent agent) {
                reached.add("create");
                return agent;
            }
            @Override public ai.mindconnect.agent.management.api.ManagedAgent update(
                    AgentId id, long expectedVersion,
                    java.util.function.UnaryOperator<ai.mindconnect.agent.management.api.ManagedAgent> change) {
                reached.add("update");
                return null;
            }
            @Override public ai.mindconnect.agent.management.api.ManagedAgent copy(
                    AgentId id) {
                reached.add("copy");
                return null;
            }
            @Override public void delete(AgentId id) {
                reached.add("delete");
            }
        };
        var guarded = new GuardedRepositories.ManagedAgents(managed,
                new NamespaceWriteGuard(namespaces, ScopeSupplier.fixed(Scope.of(ACME, ALICE))));
        var id = AgentId.of("a1");

        assertThat(guarded.list()).isEmpty();
        assertThatThrownBy(() -> guarded.create(null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> guarded.update(id, 1, agent -> agent)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> guarded.copy(id)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> guarded.delete(id)).isInstanceOf(AccessDeniedException.class);

        assertThat(reached).containsExactly("list");
    }

    @Test
    void readingIsUntouched_aUserChatsWithTheAgentsOfTheirNamespace() {
        AgentManagement guarded = guardedFor(Scope.of(ACME, ALICE));

        assertThat(guarded.list()).isEmpty();
        assertThat(guarded.findByName("helper")).isEmpty();
        assertThat(guarded.find(AgentId.of("a1"))).isEmpty();
    }

    @Test
    void workWithNoUserBehindItPasses_orAnInstallationCouldNotSeedItself() {
        guardedFor(Scope.of(ACME, null)).create(agent());

        assertThat(store.saved).as("the initial data loader, a migration, the runtime warming up")
                .containsExactly("helper");
    }

    @Test
    void theOpenDefaultNamespaceMakesEverybodyAnAdmin_soNothingChangesForASingleUserInstall() {
        guardedFor(Scope.of(Namespace.DEFAULT, ALICE)).create(agent());

        assertThat(store.saved).containsExactly("helper");
    }
}
