package ai.mindconnect.adminui.namespaces;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.Email;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.Scope;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.runtime.domain.AgentDefinition;
import ai.mindconnect.agent.runtime.port.out.AgentDefinitionRepository;
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
    private static final class Recording implements AgentDefinitionRepository {
        final List<String> saved = new ArrayList<>();
        final List<AgentId> deleted = new ArrayList<>();

        @Override public AgentDefinition save(AgentDefinition definition) {
            saved.add(definition.name());
            return definition;
        }

        @Override public void deleteById(AgentId id) {
            deleted.add(id);
        }

        @Override public Optional<AgentDefinition> findById(AgentId id) {
            return Optional.empty();
        }

        @Override public Optional<AgentDefinition> findByName(String name) {
            return Optional.empty();
        }

        @Override public List<AgentDefinition> findAll() {
            return List.of();
        }
    }

    private final Recording store = new Recording();

    NamespaceWriteGuardTest() {
        namespaces.create("acme", "ACME", DAVID);
        namespaces.invite(ACME, DAVID, Email.of("alice@acme.example"), NamespaceRole.USER);
    }

    private AgentDefinitionRepository guardedFor(Scope scope) {
        return new GuardedRepositories.Agents(store,
                new NamespaceWriteGuard(namespaces, ScopeSupplier.fixed(scope)));
    }

    private static AgentDefinition agent() {
        return AgentDefinition.create("helper", "Helps", "Be helpful.", null, null);
    }

    @Test
    void anAdminOfTheNamespaceWrites() {
        guardedFor(Scope.of(ACME, DAVID)).save(agent());

        assertThat(store.saved).containsExactly("helper");
    }

    @Test
    void aUserOfTheNamespaceDoesNot_andNothingReachesTheStore() {
        AgentDefinitionRepository guarded = guardedFor(Scope.of(ACME, ALICE));

        assertThatThrownBy(() -> guarded.save(agent()))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Only an admin of 'acme'");
        assertThatThrownBy(() -> guarded.deleteById(AgentId.of("a1")))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(store.saved).isEmpty();
        assertThat(store.deleted).isEmpty();
    }

    @Test
    void readingIsUntouched_aUserChatsWithTheAgentsOfTheirNamespace() {
        AgentDefinitionRepository guarded = guardedFor(Scope.of(ACME, ALICE));

        assertThat(guarded.findAll()).isEmpty();
        assertThat(guarded.findByName("helper")).isEmpty();
        assertThat(guarded.findById(AgentId.of("a1"))).isEmpty();
    }

    @Test
    void workWithNoUserBehindItPasses_orAnInstallationCouldNotSeedItself() {
        guardedFor(Scope.of(ACME, null)).save(agent());

        assertThat(store.saved).as("the initial data loader, a migration, the runtime warming up")
                .containsExactly("helper");
    }

    @Test
    void theOpenDefaultNamespaceMakesEverybodyAnAdmin_soNothingChangesForASingleUserInstall() {
        guardedFor(Scope.of(Namespace.DEFAULT, ALICE)).save(agent());

        assertThat(store.saved).containsExactly("helper");
    }
}
