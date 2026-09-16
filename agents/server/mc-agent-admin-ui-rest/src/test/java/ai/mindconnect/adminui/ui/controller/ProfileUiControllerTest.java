package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.adminui.ui.page.NamespacesPage;
import ai.mindconnect.adminui.ui.page.ProfilePage;
import ai.mindconnect.adminui.service.NamespaceMembers;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.namespace.adapter.memory.InMemoryNamespaceRepository;
import ai.mindconnect.namespace.service.NamespaceService;
import ai.mindconnect.user.adapter.memory.InMemoryApiTokenRepository;
import ai.mindconnect.user.adapter.memory.InMemoryUserRepository;
import ai.mindconnect.user.domain.ApiToken;
import ai.mindconnect.user.service.ApiTokenService;
import ai.mindconnect.user.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The profile page issues a token's secret exactly once, never lists it, and
 * lets a user revoke only their own tokens. Without authentication it says
 * that a token protects nothing there.
 */
class ProfileUiControllerTest {

    private static final Instant NOW = Instant.parse("2026-09-11T12:00:00Z");
    private static final Pattern SECRET = Pattern.compile("mct_[A-Za-z0-9_-]{43}");

    private final ApiTokenService tokens = new ApiTokenService(new InMemoryApiTokenRepository(),
            Clock.fixed(NOW, ZoneOffset.UTC));
    private final UserService users = new UserService(new InMemoryUserRepository());
    private final NamespaceService namespaces =
            new NamespaceService(new InMemoryNamespaceRepository(), Namespace.DEFAULT, Clock.fixed(NOW, ZoneOffset.UTC));
    private final ProfileUiController controller = controller(true);

    private ProfileUiController controller(boolean authEnabled) {
        return new ProfileUiController(tokens, users, namespaces, new NamespaceMembers(namespaces, users),
                ScopeSupplier.fixed(Namespace.DEFAULT), Clock.fixed(NOW, ZoneOffset.UTC), authEnabled);
    }

    @Test
    void aCreatedTokenIsShownOnceAndListedWithoutItsSecret() throws Exception {
        String created = json(controller.create(user("alice"), Map.of("name", "laptop", "expiresIn", "30")));

        Matcher secret = SECRET.matcher(created);
        assertThat(secret.find()).as("the dialog shows the secret").isTrue();
        assertThat(created).contains("mc-copy-field");
        assertThat(tokens.authenticate(secret.group())).map(ApiToken::userId).contains(UserId.of("alice"));

        List<ApiToken> listed = tokens.list(UserId.of("alice"));
        assertThat(listed).singleElement().satisfies(token -> {
            assertThat(token.name()).isEqualTo("laptop");
            assertThat(token.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
        });

        String page = json(controller.profile(user("alice")));
        assertThat(page).contains("laptop").contains(listed.get(0).hint())
                .doesNotContain(secret.group()).doesNotContain(listed.get(0).tokenHash());
    }

    @Test
    void withoutAuthenticationThePageAndTheDialogSayATokenProtectsNothing() throws Exception {
        ProfileUiController open = controller(false);

        assertThat(json(open.profile(user("alice")))).contains(ProfilePage.AUTH_OFF_NOTE);
        assertThat(json(open.create(user("alice"), Map.of("name", "curl", "expiresIn", "30"))))
                .contains(ProfilePage.AUTH_OFF_NOTE);

        assertThat(json(controller.profile(user("alice")))).doesNotContain(ProfilePage.AUTH_OFF_NOTE);
    }

    @Test
    void neverMeansNoExpiryAndAnUnknownChoiceTheDefault() {
        assertThat(controller.expiry("never")).isNull();
        assertThat(controller.expiry("365")).isEqualTo(NOW.plus(Duration.ofDays(365)));
        assertThat(controller.expiry("soon")).isEqualTo(NOW.plus(Duration.ofDays(90)));
    }

    @Test
    void aTokenWithoutANameIsRefusedInTheDialog() throws Exception {
        String answer = json(controller.create(user("alice"), Map.of("name", " ", "expiresIn", "never")));

        assertThat(answer).contains("A token needs a name");
        assertThat(tokens.list(UserId.of("alice"))).isEmpty();
    }

    @Test
    void onlyTheOwnerCanRevokeAToken() throws Exception {
        String secret = tokens.issue(UserId.of("alice"), "ci", null).secret();
        String tokenId = tokens.list(UserId.of("alice")).get(0).id().value();

        String bobs = json(controller.revoke(user("bob"), tokenId));
        assertThat(bobs).contains("Nothing revoked");
        assertThat(tokens.authenticate(secret)).isPresent();

        String alices = json(controller.revoke(user("alice"), tokenId));
        assertThat(alices).contains("Token revoked");
        assertThat(tokens.authenticate(secret)).isEmpty();

        assertThat(json(controller.revoke(user("alice"), "Not An Id"))).contains("Nothing revoked");
    }

    @Test
    void theListShowsOnlyTheSignedInUsersTokens() throws Exception {
        tokens.issue(UserId.of("bob"), "bobs-token", null);

        assertThat(json(controller.profile(user("alice")))).doesNotContain("bobs-token");
    }

    @Test
    void theProfileListsTheUsersNamespacesAndOpensTheInviteDialogOnlyForTheOnesTheyCreated() throws Exception {
        namespaces.create("acme", "ACME Corp", UserId.of("alice"));
        namespaces.create("beta", null, UserId.of("bob"));
        namespaces.invite(new Namespace("beta"), UserId.of("bob"), UserId.of("alice"));
        namespaces.create("gamma", null, UserId.of("bob"));

        String page = json(controller.profile(user("alice")));

        assertThat(page).contains("ACME Corp").contains("\"beta\"").doesNotContain("gamma")
                .contains("Switch to").contains(NamespacesPage.API + "/switch/{id}");

        assertThat(json(controller.inviteDialog(user("alice"), "acme"))).contains("Invite into ACME Corp");
        assertThat(json(controller.inviteDialog(user("alice"), "beta"))).contains("Only the creator")
                .doesNotContain("Invite into");
        assertThat(json(controller.inviteDialog(user("alice"), Namespace.DEFAULT.value()))).contains("Nobody to invite");
    }

    @Test
    void theCreatorInvitesByUserNameAndAnUnknownNameIsRefused() throws Exception {
        users.recordLogin(UserId.of("bob"), "sub-bob", null, "Bob", null);
        namespaces.create("acme", null, UserId.of("alice"));

        String invited = json(controller.invite(user("alice"), "acme", Map.of("user", " bob ")));
        assertThat(invited).contains("Invited").contains("Bob may now work in 'acme'");
        assertThat(namespaces.canAccess(UserId.of("bob"), new Namespace("acme"))).isTrue();

        String unknown = json(controller.invite(user("alice"), "acme", Map.of("user", "carol")));
        assertThat(unknown).contains("Invite into acme").contains("No user 'carol'").doesNotContain("Invited");
        assertThat(namespaces.canAccess(UserId.of("carol"), new Namespace("acme"))).isFalse();

        String again = json(controller.invite(user("alice"), "acme", Map.of("user", "bob")));
        assertThat(again).contains("already a member").contains("Invite into acme");
    }

    @Test
    void aMemberWhoDidNotCreateTheNamespaceCannotInvite() throws Exception {
        users.recordLogin(UserId.of("carol"), "sub-carol", null, null, null);
        namespaces.create("acme", null, UserId.of("alice"));
        namespaces.invite(new Namespace("acme"), UserId.of("alice"), UserId.of("bob"));

        String refused = json(controller.invite(user("bob"), "acme", Map.of("user", "carol")));

        assertThat(refused).contains("Only the creator");
        assertThat(namespaces.canAccess(UserId.of("carol"), new Namespace("acme"))).isFalse();
    }

    @Test
    void aNonCreatorLearnsNothingAboutWhoIsAUserHere() throws Exception {
        users.recordLogin(UserId.of("carol"), "sub-carol", null, null, null);
        namespaces.create("acme", null, UserId.of("alice"));

        String known = json(controller.invite(user("bob"), "acme", Map.of("user", "carol")));
        String unknown = json(controller.invite(user("bob"), "acme", Map.of("user", "nobody")));

        assertThat(known).contains("Only the creator").doesNotContain("Invite into");
        assertThat(unknown).contains("Only the creator").doesNotContain("No user");
    }

    @Test
    void aMemberLeavesAndTheCreatorDeletesWithEverythingInIt() throws Exception {
        namespaces.create("acme", "ACME", UserId.of("alice"));
        namespaces.invite(new Namespace("acme"), UserId.of("alice"), UserId.of("bob"));
        users.selectNamespace(UserId.of("bob"), new Namespace("acme"));

        assertThat(json(controller.leave(user("alice"), "acme"))).contains("Not left").contains("creator");
        assertThat(json(controller.delete(user("bob"), "acme"))).contains("Not deleted").contains("Only the creator");

        assertThat(json(controller.leave(user("bob"), "acme"))).contains("Left");
        assertThat(namespaces.canAccess(UserId.of("bob"), new Namespace("acme"))).isFalse();
        assertThat(users.activeNamespace(UserId.of("bob"))).contains(Namespace.DEFAULT);

        assertThat(json(controller.delete(user("alice"), "acme"))).contains("Deleted");
        assertThat(namespaces.find(new Namespace("acme"))).isEmpty();
        assertThat(json(controller.delete(user("alice"), Namespace.DEFAULT.value()))).contains("Not deleted");
    }

    @Test
    void leavingTheNamespaceYouAreInSendsYouToTheDefaultOne() {
        namespaces.create("acme", null, UserId.of("alice"));
        namespaces.invite(new Namespace("acme"), UserId.of("alice"), UserId.of("bob"));
        users.selectNamespace(UserId.of("bob"), new Namespace("acme"));
        ProfileUiController inAcme = new ProfileUiController(tokens, users, namespaces, new NamespaceMembers(namespaces, users),
                ScopeSupplier.fixed(new Namespace("acme")), Clock.fixed(NOW, ZoneOffset.UTC), true);

        var answer = inAcme.leave(user("bob"), "acme");

        assertThat(answer.getStatusCode().value()).isEqualTo(303);
        assertThat(answer.getHeaders().getLocation()).isEqualTo(NamespaceUiController.AFTER_SWITCH);
        assertThat(users.activeNamespace(UserId.of("bob"))).contains(Namespace.DEFAULT);
    }

    @Test
    void aUserAddsAndRemovesTheirOwnVariables_andNeverSeesAValueAgain() throws Exception {
        assertThat(json(controller.newVariable())).contains("Add variable");

        String added = json(controller.addVariable(user("alice"), Map.of("name", " OPENAI_API_KEY ", "value", "sk-secret")));

        assertThat(added).contains("OPENAI_API_KEY").contains("Variable saved").doesNotContain("sk-secret");
        assertThat(users.environment(UserId.of("alice"))).containsEntry("OPENAI_API_KEY", "sk-secret");
        assertThat(json(controller.profile(user("alice")))).contains("OPENAI_API_KEY").doesNotContain("sk-secret");

        assertThat(json(controller.addVariable(user("alice"), Map.of("name", "not a name", "value", "x"))))
                .contains("not a variable name");

        assertThat(json(controller.removeVariable(user("alice"), "OPENAI_API_KEY"))).contains("Variable removed");
        assertThat(users.environment(UserId.of("alice"))).isEmpty();
        assertThat(json(controller.removeVariable(user("alice"), "OPENAI_API_KEY"))).contains("Nothing removed");
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
