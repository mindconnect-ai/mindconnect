package ai.mindconnect.namespace.service;

import ai.mindconnect.agent.Email;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.namespace.domain.Actor;
import ai.mindconnect.namespace.domain.NamespaceDefinition;
import ai.mindconnect.namespace.domain.NamespaceRole;
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
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NamespaceServiceTest {

    static final class MapRepository implements NamespaceRepository {
        final Map<Namespace, NamespaceDefinition> byId = new LinkedHashMap<>();

        @Override public Optional<NamespaceDefinition> findById(Namespace id) { return Optional.ofNullable(byId.get(id)); }

        @Override public List<NamespaceDefinition> findAll() {
            return byId.values().stream().sorted(Comparator.comparing(n -> n.id().value())).toList();
        }

        @Override public List<NamespaceDefinition> findFor(Actor who) {
            return findAll().stream().filter(n -> n.isMember(who)).toList();
        }

        @Override public void save(NamespaceDefinition namespace) { byId.put(namespace.id(), namespace); }

        @Override public synchronized boolean insert(NamespaceDefinition namespace) { return byId.putIfAbsent(namespace.id(), namespace) == null; }

        @Override public boolean deleteById(Namespace id) { return byId.remove(id) != null; }
    }

    private static final UserId DAVID = UserId.of("david");
    private static final UserId ALICE = UserId.of("alice");
    private static final UserId BOB = UserId.of("bob");
    // No user store here, so an actor is its id at the installation's domain.
    private static final Email DAVIDS = Email.of("david@local");
    private static final Email ALICES = Email.of("alice@local");
    private static final Email BOBS = Email.of("bob@local");
    private static final Instant NOW = Instant.parse("2026-09-15T12:00:00Z");
    private static final Namespace ACME = new Namespace("acme");

    private final MapRepository repository = new MapRepository();
    private final NamespaceService service =
            new NamespaceService(repository, Namespace.DEFAULT, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void onlyAnAdminSetsTheVariables_andNotOnTheDefaultNamespace() {
        service.create("acme", null, DAVID);
        service.invite(ACME, DAVID, ALICES, NamespaceRole.USER);

        NamespaceDefinition updated = service.setEnvironment(new Namespace("acme"), DAVID, Map.of("OPENAI_API_KEY", "sk-acme"));

        assertThat(updated.environment()).containsEntry("OPENAI_API_KEY", "sk-acme");
        assertThat(repository.findById(new Namespace("acme"))).get().extracting(NamespaceDefinition::environment)
                .isEqualTo(Map.of("OPENAI_API_KEY", "sk-acme"));
        assertThatThrownBy(() -> service.setEnvironment(ACME, ALICE, Map.of("X", "y")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Only an admin");
        assertThatThrownBy(() -> service.setEnvironment(new Namespace("acme"), DAVID, Map.of("bad name", "y")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bad name");
        assertThatThrownBy(() -> service.setEnvironment(Namespace.DEFAULT, DAVID, Map.of("X", "y")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("default namespace");

        assertThat(service.putVariable(new Namespace("acme"), DAVID, "TAVILY_API_KEY", "tvly").environment())
                .containsKeys("OPENAI_API_KEY", "TAVILY_API_KEY");
        assertThat(service.removeVariable(new Namespace("acme"), DAVID, "OPENAI_API_KEY")).get()
                .extracting(NamespaceDefinition::environment).isEqualTo(Map.of("TAVILY_API_KEY", "tvly"));
        assertThat(service.removeVariable(new Namespace("acme"), DAVID, "NOPE")).isEmpty();
        assertThatThrownBy(() -> service.removeVariable(ACME, ALICE, "NOPE"))
                .as("the right to remove is checked before the name").hasMessageContaining("Only an admin");
    }

    @Test
    void theDefaultNamespaceIsOpenToEveryoneAndGetsARecordOnFirstUse() {
        assertThat(service.canAccess(ALICE, Namespace.DEFAULT)).isTrue();
        assertThat(service.forUser(ALICE)).extracting(NamespaceDefinition::id).containsExactly(Namespace.DEFAULT);
        assertThat(repository.findById(Namespace.DEFAULT)).isPresent()
                .get().extracting(NamespaceDefinition::createdBy).isNull();
    }

    @Test
    void createMakesAnEmptyNamespaceWithTheCreatorAsItsOnlyAdmin() {
        NamespaceDefinition acme = service.create("acme", "ACME Corp", DAVID);

        assertThat(acme.id()).isEqualTo(ACME);
        assertThat(acme.label()).isEqualTo("ACME Corp");
        assertThat(acme.createdBy()).isEqualTo(DAVIDS);
        assertThat(acme.admins()).containsExactly(DAVIDS);
        assertThat(acme.users()).isEmpty();
        assertThat(service.role(DAVID, ACME)).contains(NamespaceRole.ADMIN);
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
    void anAdminInvitesAndTheInviteeMayThenWorkThere() {
        service.create("acme", null, DAVID);

        assertThat(service.canAccess(ALICE, ACME)).isFalse();
        service.invite(ACME, DAVID, ALICES, NamespaceRole.USER);

        assertThat(service.canAccess(ALICE, ACME)).isTrue();
        assertThat(service.role(ALICE, ACME)).contains(NamespaceRole.USER);
        assertThat(service.isAdmin(ALICE, ACME)).isFalse();
        assertThat(service.forUser(ALICE)).extracting(NamespaceDefinition::id)
                .containsExactly(Namespace.DEFAULT, new Namespace("acme"));
    }

    @Test
    void onlyAnAdminInvites() {
        service.create("acme", null, DAVID);

        assertThatThrownBy(() -> service.invite(ACME, ALICE, BOBS, NamespaceRole.USER))
                .hasMessageContaining("not a member");
        service.invite(ACME, DAVID, ALICES, NamespaceRole.USER);
        assertThatThrownBy(() -> service.invite(ACME, ALICE, BOBS, NamespaceRole.USER))
                .hasMessageContaining("Only an admin");
        assertThat(service.canAccess(BOB, ACME)).isFalse();
        assertThatThrownBy(() -> service.invite(Namespace.DEFAULT, DAVID, ALICES, NamespaceRole.USER))
                .hasMessageContaining("open to everyone");
        assertThatThrownBy(() -> service.invite(new Namespace("nope"), DAVID, ALICES, NamespaceRole.USER))
                .hasMessageContaining("No namespace");
    }

    @Test
    void anInvitedAdminShapesTheNamespaceButDoesNotDeleteIt() {
        service.create("acme", null, DAVID);
        service.invite(ACME, DAVID, ALICES, NamespaceRole.ADMIN);

        assertThat(service.isAdmin(ALICE, ACME)).isTrue();
        service.invite(ACME, ALICE, BOBS, NamespaceRole.USER);
        assertThat(service.rename(ACME, ALICE, "ACME").label()).isEqualTo("ACME");
        assertThat(service.setEnvironment(ACME, ALICE, Map.of("K", "v")).environment()).containsKey("K");
        assertThatThrownBy(() -> service.delete(ACME, ALICE))
                .as("deleting stays with the creator").hasMessageContaining("Only the creator");
    }

    @Test
    void anAdminPromotesAndDemotes_butTheCreatorStaysAnAdmin() {
        service.create("acme", null, DAVID);
        service.invite(ACME, DAVID, ALICES, NamespaceRole.USER);

        service.promote(ACME, DAVID, ALICES);
        assertThat(service.role(ALICE, ACME)).contains(NamespaceRole.ADMIN);

        service.demote(ACME, ALICE, ALICES);
        assertThat(service.role(ALICE, ACME)).as("an admin may step back themselves")
                .contains(NamespaceRole.USER);
        assertThatThrownBy(() -> service.demote(ACME, DAVID, DAVIDS)).hasMessageContaining("stays an admin");
        assertThatThrownBy(() -> service.promote(ACME, ALICE, BOBS))
                .as("a user promotes nobody").hasMessageContaining("Only an admin");
    }

    @Test
    void invitingSomebodyWhoIsAlreadyAnAdminDoesNotTakeThatAway() {
        service.create("acme", null, DAVID);
        service.invite(ACME, DAVID, ALICES, NamespaceRole.ADMIN);

        service.invite(ACME, DAVID, ALICES, NamespaceRole.USER);

        assertThat(service.role(ALICE, ACME)).contains(NamespaceRole.ADMIN);
    }

    @Test
    void anAddressIsListedBeforeItsFirstSignIn_andMatchesOnceTheAccountArrives() {
        service.create("acme", null, DAVID);
        Email guest = Email.of("Guest@Example.COM");

        service.invite(ACME, DAVID, guest, NamespaceRole.USER);

        assertThat(repository.findById(ACME).orElseThrow().users())
                .as("kept lower-case, so one address is one person").containsExactly(Email.of("guest@example.com"));
        NamespaceService withStore = new NamespaceService(repository, Namespace.DEFAULT,
                Clock.fixed(NOW, ZoneOffset.UTC), List.of(),
                id -> id.equals(UserId.of("g")) ? Optional.of(Email.of("guest@example.com")) : Optional.empty(),
                "local");
        assertThat(withStore.role(UserId.of("g"), ACME)).contains(NamespaceRole.USER);
    }

    @Test
    void aBareNameBecomesAnAddressAtTheInstallationsDomain() {
        NamespaceService erni = new NamespaceService(repository, Namespace.DEFAULT, Clock.fixed(NOW, ZoneOffset.UTC),
                List.of(), id -> Optional.empty(), "@erni.mindconnect.ai");

        assertThat(erni.address("David")).isEqualTo(Email.of("david@erni.mindconnect.ai"));
        assertThat(erni.address("guest@example.com")).isEqualTo(Email.of("guest@example.com"));
        assertThat(erni.actor(DAVID).email()).isEqualTo(Email.of("david@erni.mindconnect.ai"));
        assertThat(erni.emailDomain()).isEqualTo("erni.mindconnect.ai");
    }

    @Test
    void anAdminRemovesMembersAndUsersRemoveThemselves() {
        service.create("acme", null, DAVID);
        service.invite(ACME, DAVID, ALICES, NamespaceRole.USER);
        service.invite(ACME, DAVID, BOBS, NamespaceRole.USER);

        assertThatThrownBy(() -> service.removeMember(ACME, ALICE, BOBS)).hasMessageContaining("Only an admin");
        service.removeMember(ACME, ALICE, ALICES);
        service.removeMember(ACME, DAVID, BOBS);

        assertThat(repository.findById(ACME).orElseThrow())
                .satisfies(ns -> assertThat(ns.admins()).containsExactly(DAVIDS))
                .satisfies(ns -> assertThat(ns.users()).isEmpty());
        assertThatThrownBy(() -> service.removeMember(ACME, DAVID, DAVIDS)).hasMessageContaining("creator");
    }

    @Test
    void aMemberLeavesButTheCreatorCannot() {
        service.create("acme", null, DAVID);
        service.invite(ACME, DAVID, ALICES, NamespaceRole.USER);

        service.leave(ACME, ALICE);
        assertThat(service.canAccess(ALICE, ACME)).isFalse();
        assertThatThrownBy(() -> service.leave(ACME, DAVID)).hasMessageContaining("creator");
    }

    @Test
    void theCreatorDeletesTheNamespaceAfterEveryStorePurgedIt() {
        List<Namespace> purged = new ArrayList<>();
        NamespaceService withPurges = new NamespaceService(repository, Namespace.DEFAULT, Clock.fixed(NOW, ZoneOffset.UTC),
                List.of(purged::add, purged::add));
        withPurges.create("acme", null, DAVID);
        withPurges.invite(ACME, DAVID, ALICES, NamespaceRole.USER);

        assertThatThrownBy(() -> withPurges.delete(ACME, ALICE)).hasMessageContaining("Only the creator");
        assertThatThrownBy(() -> withPurges.delete(Namespace.DEFAULT, DAVID))
                .as("the installation works there; it is nobody's to throw away")
                .hasMessageContaining("cannot be deleted");
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

    /** A repository whose save of a record holding {@code RACE} stops until released — a write caught between read and save. */
    static final class HeldSaveRepository implements NamespaceRepository {
        final Map<Namespace, NamespaceDefinition> byId = new java.util.concurrent.ConcurrentHashMap<>();
        final CountDownLatch saving = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override public Optional<NamespaceDefinition> findById(Namespace id) { return Optional.ofNullable(byId.get(id)); }

        @Override public List<NamespaceDefinition> findAll() { return List.copyOf(byId.values()); }

        @Override public List<NamespaceDefinition> findFor(Actor who) {
            return findAll().stream().filter(n -> n.isMember(who)).toList();
        }

        @Override public void save(NamespaceDefinition namespace) {
            if (namespace.environment().containsKey("RACE")) {
                saving.countDown();
                awaitQuietly(release);
            }
            byId.put(namespace.id(), namespace);
        }

        @Override public boolean insert(NamespaceDefinition namespace) { return byId.putIfAbsent(namespace.id(), namespace) == null; }

        @Override public boolean deleteById(Namespace id) { return byId.remove(id) != null; }
    }

    @Test
    void aDeleteWhileAVariableIsBeingSavedDoesNotBringTheNamespaceBack() throws Exception {
        HeldSaveRepository held = new HeldSaveRepository();
        NamespaceService racing = new NamespaceService(held, Namespace.DEFAULT, Clock.fixed(NOW, ZoneOffset.UTC),
                List.of(ns -> { }));
        Namespace acme = racing.create("acme", null, DAVID).id();

        AtomicReference<Throwable> writeFailure = new AtomicReference<>();
        AtomicReference<Throwable> deleteFailure = new AtomicReference<>();
        Thread writer = start(() -> racing.putVariable(acme, DAVID, "RACE", "1"), writeFailure);
        held.saving.await();                                     // read the record, now saving it back
        Thread deleter = start(() -> racing.delete(acme, DAVID), deleteFailure);
        awaitBlockedOrDone(deleter);                             // waiting for the write, or — unguarded — finished
        held.release.countDown();
        writer.join();
        deleter.join();

        assertThat(writeFailure.get()).isNull();
        assertThat(deleteFailure.get()).isNull();
        assertThat(held.findById(acme)).as("the deleted namespace stays deleted").isEmpty();
    }

    @Test
    void aWriteThatWaitedForTheDeleteFindsNoNamespaceAndWritesNothing() throws Exception {
        CountDownLatch purging = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        NamespaceService deleting = new NamespaceService(repository, Namespace.DEFAULT, Clock.fixed(NOW, ZoneOffset.UTC),
                List.of(ns -> { purging.countDown(); awaitQuietly(release); }));
        Namespace acme = deleting.create("acme", null, DAVID).id();

        AtomicReference<Throwable> deleteFailure = new AtomicReference<>();
        AtomicReference<Throwable> writeFailure = new AtomicReference<>();
        Thread deleter = start(() -> deleting.delete(acme, DAVID), deleteFailure);
        purging.await();                                         // the delete is under way
        Thread writer = start(() -> deleting.putVariable(acme, DAVID, "LATE", "1"), writeFailure);
        awaitBlockedOrDone(writer);
        release.countDown();
        deleter.join();
        writer.join();

        assertThat(deleteFailure.get()).isNull();
        assertThat(writeFailure.get()).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No namespace 'acme'");
        assertThat(repository.findById(acme)).isEmpty();
    }

    private static Thread start(Runnable body, AtomicReference<Throwable> failure) {
        return Thread.ofPlatform().start(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                failure.set(t);
            }
        });
    }

    /** Spins until {@code thread} waits for a monitor or has finished — no sleeping, no timing guess. */
    private static void awaitBlockedOrDone(Thread thread) {
        while (thread.getState() != Thread.State.BLOCKED && thread.getState() != Thread.State.TERMINATED) {
            Thread.onSpinWait();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
        assertThat(repository.findById(ACME)).get()
                .satisfies(ns -> assertThat(ns.admins()).containsExactly(ns.createdBy()));
    }

    @Test
    void theInstallationsOwnNamesAreReserved() {
        assertThatThrownBy(() -> service.create("system", null, DAVID)).hasMessageContaining("reserved");
        assertThatThrownBy(() -> service.create("ns", null, DAVID)).hasMessageContaining("reserved");
    }

    @Test
    void onlyAnAdminRenames() {
        service.create("acme", null, DAVID);
        service.invite(ACME, DAVID, ALICES, NamespaceRole.USER);

        assertThatThrownBy(() -> service.rename(ACME, ALICE, "x")).hasMessageContaining("Only an admin");
        assertThat(service.rename(ACME, DAVID, "  ACME  ").label()).isEqualTo("ACME");
        assertThat(service.rename(ACME, DAVID, " ").label()).isEqualTo("acme");
    }

    @Test
    void aNamespaceFromConfigurationHasTheAdminsItWasGiven() {
        NamespaceDefinition erni = service.create("erni", "ERNI AI",
                List.of(Email.of("david@erni.example"), Email.of("chief@erni.example")));

        assertThat(erni.admins()).containsExactlyInAnyOrder(
                Email.of("david@erni.example"), Email.of("chief@erni.example"));
        assertThat(erni.createdBy()).as("the first one owns it").isEqualTo(Email.of("david@erni.example"));
        assertThatThrownBy(() -> service.create("empty", null, List.<Email>of()))
                .hasMessageContaining("at least one admin");
    }

    @Test
    void namingTheAdminsOfTheDefaultNamespaceClosesIt() {
        NamespaceService closed = new NamespaceService(repository, Namespace.DEFAULT,
                Clock.fixed(NOW, ZoneOffset.UTC), List.of(), id -> Optional.empty(), "local",
                List.of(DAVIDS));

        assertThat(closed.defaultIsOpen()).isFalse();
        assertThat(closed.role(DAVID, Namespace.DEFAULT)).contains(NamespaceRole.ADMIN);
        assertThat(closed.role(ALICE, Namespace.DEFAULT)).as("being signed in is not being let in").isEmpty();
        assertThat(closed.canAccess(ALICE, Namespace.DEFAULT)).isFalse();
        assertThat(closed.forUser(ALICE)).as("nowhere to work until somebody invites them").isEmpty();
        assertThat(closed.forUser(DAVID)).extracting(NamespaceDefinition::id).containsExactly(Namespace.DEFAULT);
        assertThat(repository.findById(Namespace.DEFAULT)).get()
                .satisfies(ns -> assertThat(ns.admins()).containsExactly(DAVIDS))
                .satisfies(ns -> assertThat(ns.createdBy()).isEqualTo(DAVIDS));
    }

    @Test
    void namingAdminsLaterAddsThemToTheDefaultRecordThatAlreadyExists() {
        // Written while the default namespace was open (0.8.2, or before the admins were named): nobody listed.
        service.defaultDefinition();
        repository.save(repository.findById(Namespace.DEFAULT).orElseThrow().withUser(ALICES));
        NamespaceService closed = new NamespaceService(repository, Namespace.DEFAULT,
                Clock.fixed(NOW, ZoneOffset.UTC), List.of(), id -> Optional.empty(), "local",
                List.of(DAVIDS));

        assertThat(closed.role(DAVID, Namespace.DEFAULT)).as("the named admin is not locked out")
                .contains(NamespaceRole.ADMIN);
        assertThat(closed.role(ALICE, Namespace.DEFAULT)).as("who was already listed stays")
                .contains(NamespaceRole.USER);
        assertThat(closed.role(BOB, Namespace.DEFAULT)).isEmpty();
        assertThat(repository.findById(Namespace.DEFAULT)).get()
                .satisfies(ns -> assertThat(ns.admins()).containsExactly(DAVIDS));
    }

    @Test
    void withoutAnAdminListTheDefaultNamespaceStaysOpenToEverybody() {
        assertThat(service.defaultIsOpen()).isTrue();
        assertThat(service.role(ALICE, Namespace.DEFAULT)).contains(NamespaceRole.ADMIN);
        assertThat(service.forUser(ALICE)).extracting(NamespaceDefinition::id).containsExactly(Namespace.DEFAULT);
    }

    @Test
    void ensureCreatesOnceAndNeverChangesWhatIsAlreadyThere() {
        NamespaceService.Created first = service.ensure("erni", "ERNI AI", List.of(DAVIDS));

        assertThat(first.fresh()).isTrue();
        assertThat(first.namespace().admins()).containsExactly(DAVIDS);

        NamespaceService.Created again = service.ensure("erni", "Someone else", List.of(ALICES));

        assertThat(again.fresh()).isFalse();
        assertThat(again.namespace().label()).isEqualTo("ERNI AI");
        assertThat(again.namespace().admins()).as("a configuration edited later does not reshuffle a namespace")
                .containsExactly(DAVIDS);
    }
}
