package ai.mindconnect.adminui.service;

import ai.mindconnect.agent.Email;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.namespace.adapter.memory.InMemoryNamespaceRepository;
import ai.mindconnect.namespace.domain.NamespaceRole;
import ai.mindconnect.namespace.service.NamespaceService;
import ai.mindconnect.user.adapter.memory.InMemoryUserRepository;
import ai.mindconnect.user.service.UserService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Inviting is an admin's, and the refusal is the same whether the namespace
 * exists or not — so inviting cannot be used to find out which namespaces
 * there are, or who is in one.
 */
class NamespaceMembersTest {

    private static final Namespace ACME = new Namespace("acme");
    private static final UserId ALICE = UserId.of("alice");
    private static final UserId BOB = UserId.of("bob");
    private static final UserId MALLORY = UserId.of("mallory");

    private final NamespaceService namespaces = new NamespaceService(new InMemoryNamespaceRepository(), Namespace.DEFAULT);
    private final NamespaceMembers members = new NamespaceMembers(namespaces, new UserService(new InMemoryUserRepository()));

    NamespaceMembersTest() {
        namespaces.create("acme", "ACME", ALICE);
        namespaces.invite(ACME, ALICE, namespaces.address("bob"), NamespaceRole.USER);
    }

    @Test
    void anAdminInvites_andIsToldWhenTheAddressIsAlreadyListed() {
        assertThat(members.invite(ACME, ALICE, "carol", NamespaceRole.USER)).isEqualTo(Email.of("carol@local"));
        assertThatThrownBy(() -> members.invite(ACME, ALICE, "bob", NamespaceRole.USER))
                .hasMessageContaining("already in");
    }

    @Test
    void aStrangerLearnsNothing_notWhetherTheNamespaceExistsNorWhoIsInIt() {
        String existing = message(() -> members.invite(ACME, MALLORY, "bob", NamespaceRole.USER));
        String unlisted = message(() -> members.invite(ACME, MALLORY, "nobody", NamespaceRole.USER));
        String missing = message(() -> members.invite(new Namespace("acme"), MALLORY, "bob", NamespaceRole.USER));
        String elsewhere = message(() -> members.invite(new Namespace("nope"), MALLORY, "bob", NamespaceRole.USER));

        assertThat(existing).isEqualTo("Only an admin of 'acme' may invite into it")
                .isEqualTo(unlisted).isEqualTo(missing);
        assertThat(elsewhere).isEqualTo("Only an admin of 'nope' may invite into it");
    }

    @Test
    void aUserOfTheNamespaceIsRefusedInTheSameWords() {
        assertThat(message(() -> members.invite(ACME, BOB, "alice", NamespaceRole.USER)))
                .isEqualTo("Only an admin of 'acme' may invite into it");
        assertThat(namespaces.find(ACME)).get()
                .satisfies(ns -> assertThat(ns.users()).containsExactly(Email.of("bob@local")));
    }

    private static String message(Runnable call) {
        Throwable thrown = catchThrowable(call::run);
        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
        return thrown.getMessage();
    }
}
