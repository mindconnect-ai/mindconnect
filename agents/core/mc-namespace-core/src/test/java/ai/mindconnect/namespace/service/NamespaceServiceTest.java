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
import java.util.ArrayList;
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

        @Override public synchronized boolean insert(NamespaceDefinition namespace) { return byId.putIfAbsent(namespace.id(), namespace) == null; }

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
                .get().extracting(NamespaceDefinition::createdBy).isNull();
    }

    @Test
    void createMakesAnEmptyNamespaceWithTheCreatorAsItsOnlyMember() {
        NamespaceDefinition acme = service.create("acme", "ACME Corp", DAVID);

        assertThat(acme.id()).isEqualTo(new Namespace("acme"));
        assertThat(acme.label()).isEqualTo("ACME Corp");
        assertThat(acme.createdBy()).isEqualTo(DAVID);
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
    void theCreatorInvitesAndTheInviteeMayThenWorkThere() {
        service.create("acme", null, DAVID);

        assertThat(service.canAccess(ALICE, new Namespace("acme"))).isFalse();
        service.invite(new Namespace("acme"), DAVID, ALICE);

        assertThat(service.canAccess(ALICE, new Namespace("acme"))).isTrue();
        assertThat(service.forUser(ALICE)).extracting(NamespaceDefinition::id)
                .containsExactly(Namespace.DEFAULT, new Namespace("acme"));
    }

    @Test
    void onlyTheCreatorInvites() {
        service.create("acme", null, DAVID);

        assertThatThrownBy(() -> service.invite(new Namespace("acme"), ALICE, UserId.of("bob")))
                .hasMessageContaining("not a member");
        service.invite(new Namespace("acme"), DAVID, ALICE);
        assertThatThrownBy(() -> service.invite(new Namespace("acme"), ALICE, UserId.of("bob")))
                .hasMessageContaining("Only the creator");
        assertThat(service.canAccess(UserId.of("bob"), new Namespace("acme"))).isFalse();
        assertThatThrownBy(() -> service.invite(Namespace.DEFAULT, DAVID, ALICE))
                .hasMessageContaining("open to everyone");
        assertThatThrownBy(() -> service.invite(new Namespace("nope"), DAVID, ALICE))
                .hasMessageContaining("No namespace");
    }

    @Test
    void theCreatorRemovesMembersAndMembersRemoveThemselves() {
        service.create("acme", null, DAVID);
        Namespace acme = new Namespace("acme");
        service.invite(acme, DAVID, ALICE);
        service.invite(acme, DAVID, UserId.of("bob"));

        assertThatThrownBy(() -> service.removeMember(acme, ALICE, UserId.of("bob"))).hasMessageContaining("Only the creator");
        service.removeMember(acme, ALICE, ALICE);
        service.removeMember(acme, DAVID, UserId.of("bob"));

        assertThat(repository.findById(acme).orElseThrow().members()).containsExactly(DAVID);
        assertThatThrownBy(() -> service.removeMember(acme, DAVID, DAVID)).hasMessageContaining("creator");
    }

    @Test
    void aMemberLeavesButTheCreatorCannot() {
        service.create("acme", null, DAVID);
        service.invite(new Namespace("acme"), DAVID, ALICE);

        service.leave(new Namespace("acme"), ALICE);
        assertThat(service.canAccess(ALICE, new Namespace("acme"))).isFalse();
        assertThatThrownBy(() -> service.leave(new Namespace("acme"), DAVID)).hasMessageContaining("creator");
    }

    @Test
    void theCreatorDeletesTheNamespaceAfterEveryStorePurgedIt() {
        List<Namespace> purged = new ArrayList<>();
        NamespaceService withPurges = new NamespaceService(repository, Namespace.DEFAULT, Clock.fixed(NOW, ZoneOffset.UTC),
                List.of(purged::add, purged::add));
        withPurges.create("acme", null, DAVID);
        withPurges.invite(new Namespace("acme"), DAVID, ALICE);

        assertThatThrownBy(() -> withPurges.delete(new Namespace("acme"), ALICE)).hasMessageContaining("Only the creator");
        assertThatThrownBy(() -> withPurges.delete(Namespace.DEFAULT, DAVID)).hasMessageContaining("open to everyone");
        assertThat(repository.findById(new Namespace("acme"))).isPresent();

        withPurges.delete(new Namespace("acme"), DAVID);

        assertThat(purged).containsExactly(new Namespace("acme"), new Namespace("acme"));
        assertThat(repository.findById(new Namespace("acme"))).isEmpty();
        assertThat(withPurges.forUser(ALICE)).extracting(NamespaceDefinition::id).containsExactly(Namespace.DEFAULT);
    }

    @Test
    void aStoreThatCannotPurgeKeepsTheRecordSoTheDeletionCanBeRetried() {
        NamespaceService failing = new NamespaceService(repository, Namespace.DEFAULT, Clock.fixed(NOW, ZoneOffset.UTC),
                List.of(ns -> { throw new IllegalStateException("disk gone"); }));
        failing.create("acme", null, DAVID);

        assertThatThrownBy(() -> failing.delete(new Namespace("acme"), DAVID))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("disk gone");
        assertThat(repository.findById(new Namespace("acme"))).isPresent();
    }

    @Test
    void twoUsersCreatingTheSameIdAtOnceGetOneNamespace() throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<Boolean> david = () -> { go.await(); try { service.create("acme", null, DAVID); return true; } catch (IllegalArgumentException e) { return false; } };
        java.util.concurrent.Callable<Boolean> alice = () -> { go.await(); try { service.create("acme", null, ALICE); return true; } catch (IllegalArgumentException e) { return false; } };
        var first = pool.submit(david);
        var second = pool.submit(alice);
        go.countDown();

        int created = (first.get() ? 1 : 0) + (second.get() ? 1 : 0);
        pool.shutdown();

        assertThat(created).isEqualTo(1);
        assertThat(repository.findById(new Namespace("acme"))).get()
                .satisfies(ns -> assertThat(ns.members()).containsExactly(ns.createdBy()));
    }

    @Test
    void theInstallationsOwnNamesAreReserved() {
        assertThatThrownBy(() -> service.create("system", null, DAVID)).hasMessageContaining("reserved");
        assertThatThrownBy(() -> service.create("ns", null, DAVID)).hasMessageContaining("reserved");
    }

    @Test
    void onlyTheCreatorRenames() {
        service.create("acme", null, DAVID);
        service.invite(new Namespace("acme"), DAVID, ALICE);

        assertThatThrownBy(() -> service.rename(new Namespace("acme"), ALICE, "x")).hasMessageContaining("Only the creator");
        assertThat(service.rename(new Namespace("acme"), DAVID, "  ACME  ").label()).isEqualTo("ACME");
        assertThat(service.rename(new Namespace("acme"), DAVID, " ").label()).isEqualTo("acme");
    }
}
