package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.adminui.ui.page.ProfilePage;
import ai.mindconnect.agent.UserId;
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
    private final ProfileUiController controller = controller(true);

    private ProfileUiController controller(boolean authEnabled) {
        return new ProfileUiController(tokens, new UserService(new InMemoryUserRepository()),
                Clock.fixed(NOW, ZoneOffset.UTC), authEnabled);
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
