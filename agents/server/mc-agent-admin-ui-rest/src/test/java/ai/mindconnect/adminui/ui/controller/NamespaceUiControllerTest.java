package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.adminui.service.NamespaceMembers;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.starter.namespace.NamespaceSelection;
import ai.mindconnect.namespace.adapter.memory.InMemoryNamespaceRepository;
import ai.mindconnect.namespace.service.NamespaceService;
import ai.mindconnect.user.adapter.memory.InMemoryUserRepository;
import ai.mindconnect.user.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The namespaces screen acts as the signed-in user: a switch is refused
 * outside their namespaces, a switch or a creation is remembered on the
 * session and on the user, and membership changes are the creator's.
 */
class NamespaceUiControllerTest {

    private static final Instant NOW = Instant.parse("2026-09-15T12:00:00Z");
    private static final Namespace ACME = new Namespace("acme");

    private final InMemoryUserRepository userStore = new InMemoryUserRepository();
    private final UserService users = new UserService(userStore);
    private final NamespaceService namespaces = new NamespaceService(new InMemoryNamespaceRepository(), Namespace.DEFAULT);
    private final NamespaceUiController controller = new NamespaceUiController(namespaces, userStore, users,
            new NamespaceMembers(namespaces, users), ScopeSupplier.fixed(Namespace.DEFAULT));

    @Test
    void aMemberSwitchesAndTheChoiceIsRememberedOnSessionAndUser() {
        namespaces.create("acme", null, UserId.of("alice"));
        MockHttpServletRequest request = new MockHttpServletRequest();

        ResponseEntity<Void> answer = controller.switchTo(user("alice"), "acme", request);

        assertThat(answer.getStatusCode().value()).isEqualTo(303);
        assertThat(answer.getHeaders().getLocation()).isEqualTo(NamespaceUiController.AFTER_SWITCH);
        assertThat(NamespaceSelection.selected(request)).contains(ACME);
        assertThat(users.activeNamespace(UserId.of("alice"))).contains(ACME);
    }

    @Test
    void aNonMemberAndAnUnknownIdAreBothRefused_soNobodyLearnsWhichNamespacesExist() {
        namespaces.create("acme", null, UserId.of("alice"));
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThat(controller.switchTo(user("bob"), "acme", request).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.switchTo(user("bob"), "nope", request).getStatusCode().value()).isEqualTo(403);
        assertThat(NamespaceSelection.selected(request)).isEmpty();
        assertThat(users.activeNamespace(UserId.of("bob"))).isEmpty();
    }

    @Test
    void createMakesTheNamespaceAndEntersIt_aBadIdKeepsTheDialogOpen() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();

        ResponseEntity<Object> created = controller.create(user("alice"), Map.of("id", "acme", "displayName", "ACME"), request);

        assertThat(created.getStatusCode().value()).isEqualTo(303);
        assertThat(namespaces.find(ACME)).get().satisfies(ns -> {
            assertThat(ns.createdBy()).isEqualTo(UserId.of("alice"));
            assertThat(ns.label()).isEqualTo("ACME");
        });
        assertThat(NamespaceSelection.selected(request)).contains(ACME);
        assertThat(users.activeNamespace(UserId.of("alice"))).contains(ACME);

        ResponseEntity<Object> refused = controller.create(user("alice"), Map.of("id", "Acme"), request);
        assertThat(refused.getStatusCode().value()).isEqualTo(200);
        assertThat(json(refused.getBody())).contains("lower-case").contains(NamespaceUiController.CREATE_DIALOG_ID);
        assertThat(json(controller.create(user("bob"), Map.of("id", "acme"), request).getBody())).contains("already exists");
    }

    @Test
    void onlyTheCreatorRemovesOthers_andAMemberRemovesThemselves() throws Exception {
        namespaces.create("acme", null, UserId.of("alice"));
        namespaces.invite(ACME, UserId.of("alice"), UserId.of("bob"));
        namespaces.invite(ACME, UserId.of("alice"), UserId.of("carol"));
        users.selectNamespace(UserId.of("carol"), ACME);

        assertThat(json(controller.remove(user("bob"), "acme", "carol"))).contains("Not removed").contains("Only the creator");
        assertThat(namespaces.canAccess(UserId.of("carol"), ACME)).isTrue();

        assertThat(json(controller.remove(user("alice"), "acme", "carol"))).contains("Removed");
        assertThat(namespaces.canAccess(UserId.of("carol"), ACME)).isFalse();
        assertThat(users.activeNamespace(UserId.of("carol"))).as("a removed member is back in the default namespace")
                .contains(Namespace.DEFAULT);

        assertThat(json(controller.remove(user("bob"), "acme", "bob"))).contains("Removed");
        assertThat(namespaces.canAccess(UserId.of("bob"), ACME)).isFalse();
        assertThat(json(controller.remove(user("alice"), "acme", "alice"))).contains("Not removed").contains("creator");
    }

    @Test
    void thePageListsTheUsersNamespacesWithTheCreatorsActionsOnly() throws Exception {
        namespaces.create("acme", "ACME", UserId.of("alice"));
        namespaces.invite(ACME, UserId.of("alice"), UserId.of("bob"));
        namespaces.create("beta", null, UserId.of("carol"));

        String alices = json(controller.page(user("alice")));
        String bobs = json(controller.page(user("bob")));

        assertThat(alices).contains("ACME").doesNotContain("beta").contains("Delete namespace").doesNotContain("\"Leave\"");
        assertThat(bobs).contains("ACME").contains("\"Leave\"").doesNotContain("Delete namespace")
                .doesNotContain("namespace-acme-invite");
    }

    private static OidcUser user(String name) {
        OidcIdToken idToken = OidcIdToken.withTokenValue("id").subject("sub-" + name)
                .claim(StandardClaimNames.PREFERRED_USERNAME, name)
                .issuedAt(NOW).expiresAt(NOW.plusSeconds(60)).build();
        return new DefaultOidcUser(AuthorityUtils.createAuthorityList("ROLE_USER"), idToken);
    }

    private static String json(Object node) throws Exception {
        return new ObjectMapper().findAndRegisterModules().writeValueAsString(node);
    }
}
