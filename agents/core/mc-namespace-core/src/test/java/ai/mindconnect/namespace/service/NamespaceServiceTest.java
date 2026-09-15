package ai.mindconnect.namespace.service;

import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.namespace.domain.NamespaceDefinition;
import ai.mindconnect.namespace.port.out.NamespaceRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NamespaceServiceTest {

    static final class MapRepository implements NamespaceRepository {
        final Map<Namespace, NamespaceDefinition> byId = new LinkedHashMap<>();

        @Override public Optional<NamespaceDefinition> findById(Namespace id) { return Optional.ofNullable(byId.get(id)); }

        @Override public List<NamespaceDefinition> findAll() {
            return byId.values().stream().sorted(Comparator.comparing(n -> n.id().value())).toList();
        }

        @Override public List<NamespaceDefinition> findByMember(UserId user) {
            return findAll().stream().filter(n -> n.isMember(user)).toList();
        }

        @Override public void save(NamespaceDefinition namespace) { byId.put(namespace.id(), namespace); }

        @Override public boolean deleteById(Namespace id) { return byId.remove(id) != null; }
    }

    private static final UserId DAVID = UserId.of("david");
    private static final UserId ALICE = UserId.of("alice");
    private static final Instant NOW = Instant.parse("2026-09-15T12:00:00Z");

    private final MapRepository repository = new MapRepository();
    private final NamespaceService service =
            new NamespaceService(repository, Namespace.DEFAULT, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void theDefaultNamespaceIsOpenToEveryoneAndGetsARecordOnFirstUse() {
        assertThat(service.canAccess(ALICE, Namespace.DEFAULT)).isTrue();
        assertThat(service.forUser(ALICE)).extracting(NamespaceDefinition::id).containsExactly(Namespace.DEFAULT);
        assertThat(repository.findById(Namespace.DEFAULT)).isPresent()
                .get().extracting(NamespaceDefinition::owner).isNull();
    }

    @Test
    void createMakesAnEmptyNamespaceOwnedByTheCreator() {
        NamespaceDefinition acme = service.create("acme", "ACME Corp", DAVID);

        assertThat(acme.id()).isEqualTo(new Namespace("acme"));
        assertThat(acme.label()).isEqualTo("ACME Corp");
        assertThat(acme.owner()).isEqualTo(DAVID);
        assertThat(acme.members()).containsExactly(DAVID);
        assertThat(acme.createdAt()).isEqualTo(NOW);
        assertThat(service.forUser(DAVID)).extracting(NamespaceDefinition::id)
                .containsExactly(Namespace.DEFAULT, new Namespace("acme"));
        assertThat(service.forUser(ALICE)).extracting(NamespaceDefinition::id).containsExactly(Namespace.DEFAULT);
    }

    @Test
    void createRefusesBadIdsAndDuplicates() {
        service.create("acme", null, DAVID);

        assertThatThrownBy(() -> service.create("acme", null, ALICE)).hasMessageContaining("already exists");
        assertThatThrownBy(() -> service.create("local", null, ALICE)).hasMessageContaining("already exists");
        assertThatThrownBy(() -> service.create("Acme", null, DAVID)).hasMessageContaining("lower-case");
        assertThatThrownBy(() -> service.create("a/b", null, DAVID)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create("", null, DAVID)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create("-x", null, DAVID)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aMemberInvitesAndTheInviteeMayThenWorkThere() {
        service.create("acme", null, DAVID);

        assertThat(service.canAccess(ALICE, new Namespace("acme"))).isFalse();
        service.invite(new Namespace("acme"), DAVID, ALICE);

        assertThat(service.canAccess(ALICE, new Namespace("acme"))).isTrue();
        assertThat(service.forUser(ALICE)).extracting(NamespaceDefinition::id)
                .containsExactly(Namespace.DEFAULT, new Namespace("acme"));
    }

    @Test
    void anOutsiderCannotInvite() {
        service.create("acme", null, DAVID);

        assertThatThrownBy(() -> service.invite(new Namespace("acme"), ALICE, UserId.of("bob")))
                .hasMessageContaining("not a member");
        assertThatThrownBy(() -> service.invite(Namespace.DEFAULT, DAVID, ALICE))
                .hasMessageContaining("open to everyone");
        assertThatThrownBy(() -> service.invite(new Namespace("nope"), DAVID, ALICE))
                .hasMessageContaining("No namespace");
    }

    @Test
    void theOwnerRemovesMembersAndMembersRemoveThemselves() {
        service.create("acme", null, DAVID);
        Namespace acme = new Namespace("acme");
        service.invite(acme, DAVID, ALICE);
        service.invite(acme, ALICE, UserId.of("bob"));

        assertThatThrownBy(() -> service.removeMember(acme, ALICE, UserId.of("bob"))).hasMessageContaining("Only the owner");
        service.removeMember(acme, ALICE, ALICE);
        service.removeMember(acme, DAVID, UserId.of("bob"));

        assertThat(repository.findById(acme).orElseThrow().members()).containsExactly(DAVID);
        assertThatThrownBy(() -> service.removeMember(acme, DAVID, DAVID)).hasMessageContaining("owner");
    }

    @Test
    void onlyTheOwnerRenames() {
        service.create("acme", null, DAVID);
        service.invite(new Namespace("acme"), DAVID, ALICE);

        assertThatThrownBy(() -> service.rename(new Namespace("acme"), ALICE, "x")).hasMessageContaining("Only the owner");
        assertThat(service.rename(new Namespace("acme"), DAVID, "  ACME  ").label()).isEqualTo("ACME");
        assertThat(service.rename(new Namespace("acme"), DAVID, " ").label()).isEqualTo("acme");
    }
}
