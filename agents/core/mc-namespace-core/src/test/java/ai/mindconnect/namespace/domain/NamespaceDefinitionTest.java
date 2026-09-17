package ai.mindconnect.namespace.domain;

import ai.mindconnect.agent.Email;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The record's own rules. What JSON it reads and writes — the plain addresses, and
 * a document from before the two lists — is the repositories' business and is
 * covered where a mapper exists: {@code FileNamespaceRepositoryTest}.
 */
class NamespaceDefinitionTest {

    private static final Namespace ACME = new Namespace("acme");
    private static final Instant NOW = Instant.parse("2026-09-17T12:00:00Z");
    private static final Email DAVID = Email.of("david@acme.example");
    private static final Email ALICE = Email.of("alice@acme.example");
    private static final Email BOB = Email.of("bob@acme.example");

    private static Actor who(Email address) {
        return Actor.of(UserId.of(address.value().split("@")[0]), address);
    }

    private static NamespaceDefinition acme() {
        return NamespaceDefinition.create(ACME, "ACME", DAVID, NOW);
    }

    @Test
    void theRoleIsTheListSomebodyIsIn() {
        NamespaceDefinition ns = acme().withUser(ALICE).withAdmin(BOB);

        assertThat(ns.role(who(DAVID))).contains(NamespaceRole.ADMIN);
        assertThat(ns.role(who(BOB))).contains(NamespaceRole.ADMIN);
        assertThat(ns.role(who(ALICE))).contains(NamespaceRole.USER);
        assertThat(ns.role(who(Email.of("nobody@acme.example")))).isEmpty();
        assertThat(ns.role(null)).isEmpty();
        assertThat(ns.isMember(who(ALICE))).isTrue();
        assertThat(ns.isAdmin(who(ALICE))).isFalse();
    }

    @Test
    void theCreatorIsAlwaysAnAdminAndStaysOne() {
        NamespaceDefinition ns = new NamespaceDefinition(ACME, "ACME", DAVID, NOW, Set.of(), Set.of(DAVID));

        assertThat(ns.admins()).as("the creator is not a mere user, whatever the lists say").containsExactly(DAVID);
        assertThat(ns.users()).isEmpty();
        assertThat(ns.isCreator(who(DAVID))).isTrue();
        assertThatThrownBy(() -> ns.demote(DAVID)).hasMessageContaining("stays an admin");
        assertThatThrownBy(() -> ns.without(DAVID)).hasMessageContaining("cannot be removed");
    }

    @Test
    void anAddressIsInOneListOnly() {
        NamespaceDefinition ns = acme().withUser(ALICE).withAdmin(ALICE);

        assertThat(ns.admins()).containsExactlyInAnyOrder(DAVID, ALICE);
        assertThat(ns.users()).isEmpty();

        NamespaceDefinition back = ns.demote(ALICE);
        assertThat(back.admins()).containsExactly(DAVID);
        assertThat(back.users()).containsExactly(ALICE);
    }

    @Test
    void invitingAnAdminAgainAsAUserChangesNothing() {
        NamespaceDefinition ns = acme().withAdmin(ALICE);

        assertThat(ns.withUser(ALICE)).isSameAs(ns);
        assertThat(ns.with(ALICE, NamespaceRole.USER)).isSameAs(ns);
        assertThat(ns.with(BOB, NamespaceRole.ADMIN).admins()).contains(BOB);
    }

    @Test
    void withoutTakesSomebodyOutOfEitherList_andAnUnlistedAddressIsNoChange() {
        NamespaceDefinition ns = acme().withUser(ALICE).withAdmin(BOB);

        assertThat(ns.without(ALICE).users()).isEmpty();
        assertThat(ns.without(BOB).admins()).containsExactly(DAVID);
        assertThat(ns.without(Email.of("stranger@example.com"))).isSameAs(ns);
        assertThat(ns.without(null)).isSameAs(ns);
    }

    @Test
    void entryOfAnswersWhatSomebodyIsListedUnder() {
        NamespaceDefinition ns = acme().withUser(ALICE);

        assertThat(ns.entryOf(who(ALICE))).contains(ALICE);
        assertThat(ns.entryOf(who(DAVID))).contains(DAVID);
        assertThat(ns.entryOf(who(Email.of("nobody@acme.example")))).isEmpty();
    }

    @Test
    void anAdminFromConfigurationIsTheCreatorWhenItIsTheFirstOne() {
        NamespaceDefinition erni = NamespaceDefinition.create(new Namespace("erni"), "ERNI AI",
                List.of(DAVID, ALICE), NOW);

        assertThat(erni.createdBy()).isEqualTo(DAVID);
        assertThat(erni.admins()).containsExactlyInAnyOrder(DAVID, ALICE);
        assertThat(NamespaceDefinition.create(ACME, null, List.<Email>of(), NOW).createdBy())
                .as("a namespace can exist without one — the installation's default does").isNull();
    }

    @Test
    void anAccountWithoutAnAddressIsInNoNamespace() {
        Actor nameless = Actor.of(UserId.of("david"));

        assertThat(acme().withUser(ALICE).role(nameless)).isEmpty();
        assertThat(acme().isCreator(nameless)).isFalse();
    }

}
