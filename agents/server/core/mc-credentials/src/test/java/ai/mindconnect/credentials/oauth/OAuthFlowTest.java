package ai.mindconnect.credentials.oauth;

import ai.mindconnect.credentials.domain.OAuth2UserCreds;
import ai.mindconnect.credentials.domain.OAuthProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The authorization-code exchange against a token endpoint that answers like a
 * real one. The parts worth being exact about are the ones a bug makes into a
 * security bug: the PKCE challenge, the one-time state, and never sending a
 * secret that does not exist.
 */
class OAuthFlowTest {

    private static final String REDIRECT = "https://mc.example.com/admin/oauth/callback";

    private FakeTokenEndpoint server;
    private OAuthFlow flow;

    @BeforeEach
    void setUp() throws IOException {
        server = new FakeTokenEndpoint();
        flow = new OAuthFlow(new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    // ── the authorization URL ───────────────────────────────────────────────

    @Test
    void the_authorization_url_carries_what_the_provider_needs_to_answer() {
        OAuthFlow.Start start = flow.start(publicClient(), REDIRECT, List.of("Mail.Read", "offline_access"));

        Map<String, String> query = queryOf(start.authorizeUrl());
        assertThat(query).containsEntry("response_type", "code")
                .containsEntry("client_id", "the-client-id")
                .containsEntry("redirect_uri", REDIRECT)
                .containsEntry("scope", "Mail.Read offline_access")
                .containsEntry("state", start.state())
                .containsEntry("audience", "api://example");      // extraAuthzParams
    }

    @Test
    void the_pkce_challenge_is_the_hash_of_the_verifier_and_the_verifier_stays_here() {
        OAuthFlow.Start start = flow.start(publicClient(), REDIRECT, List.of());

        Map<String, String> query = queryOf(start.authorizeUrl());
        assertThat(query).containsEntry("code_challenge_method", "S256");
        assertThat(query.get("code_challenge")).isEqualTo(OAuthFlow.challengeOf(start.codeVerifier()));
        // The verifier itself must never travel with the browser.
        assertThat(start.authorizeUrl()).doesNotContain(start.codeVerifier());
    }

    @Test
    void every_attempt_gets_its_own_state_and_verifier() {
        OAuthFlow.Start one = flow.start(publicClient(), REDIRECT, List.of());
        OAuthFlow.Start two = flow.start(publicClient(), REDIRECT, List.of());

        assertThat(one.state()).isNotEqualTo(two.state());
        assertThat(one.codeVerifier()).isNotEqualTo(two.codeVerifier());
    }

    @Test
    void a_provider_without_pkce_gets_no_challenge_and_no_verifier() {
        OAuthProvider legacy = provider("secret", false);

        OAuthFlow.Start start = flow.start(legacy, REDIRECT, List.of());

        assertThat(start.codeVerifier()).isNull();
        assertThat(queryOf(start.authorizeUrl())).doesNotContainKey("code_challenge");
    }

    @Test
    void the_default_scopes_are_used_when_the_call_names_none() {
        assertThat(queryOf(flow.start(publicClient(), REDIRECT, List.of()).authorizeUrl()))
                .containsEntry("scope", "openid offline_access");
    }

    // ── the exchange ────────────────────────────────────────────────────────

    @Test
    void the_code_becomes_tokens_with_an_expiry() {
        server.answers("""
                {"access_token":"at-1","refresh_token":"rt-1","expires_in":3600,"scope":"Mail.Read"}""");

        Instant before = Instant.now();
        OAuth2UserCreds credentials = flow.exchange(publicClient(), "the-code", "the-verifier", REDIRECT);

        assertThat(credentials.accessToken()).isEqualTo("at-1");
        assertThat(credentials.refreshToken()).isEqualTo("rt-1");
        assertThat(credentials.scopes()).containsExactly("Mail.Read");
        assertThat(credentials.expiresAt()).isBetween(before.plusSeconds(3590), Instant.now().plusSeconds(3601));

        Map<String, String> form = server.request(0);
        assertThat(form).containsEntry("grant_type", "authorization_code")
                .containsEntry("code", "the-code")
                .containsEntry("code_verifier", "the-verifier")
                .containsEntry("redirect_uri", REDIRECT)
                .containsEntry("client_id", "the-client-id");
    }

    @Test
    void a_public_client_sends_no_client_secret() {
        // An app registration several installations share cannot carry one, and
        // an empty client_secret is refused by some providers rather than ignored.
        server.answers("""
                {"access_token":"at-1","expires_in":60}""");

        flow.exchange(publicClient(), "the-code", "v", REDIRECT);

        assertThat(server.request(0)).doesNotContainKey("client_secret");
    }

    @Test
    void a_confidential_client_does_send_one() {
        server.answers("""
                {"access_token":"at-1","expires_in":60}""");

        flow.exchange(provider("the-secret", true), "the-code", "v", REDIRECT);

        assertThat(server.request(0)).containsEntry("client_secret", "the-secret");
    }

    @Test
    void a_refusal_carries_the_providers_reason_and_not_its_body() {
        server.refuses(400, """
                {"error":"invalid_grant","error_description":"code already redeemed","id_token":"leak-me"}""");

        assertThatThrownBy(() -> flow.exchange(publicClient(), "spent", "v", REDIRECT))
                .isInstanceOf(OAuthException.class)
                .hasMessageContaining("invalid_grant")
                .hasMessageContaining("code already redeemed")
                .hasMessageNotContaining("leak-me");
    }

    @Test
    void an_answer_without_a_token_is_a_refusal_too() {
        server.answers("""
                {"token_type":"Bearer"}""");

        assertThatThrownBy(() -> flow.exchange(publicClient(), "c", "v", REDIRECT))
                .isInstanceOf(OAuthException.class)
                .hasMessageContaining("without an access token");
    }

    // ── refreshing ──────────────────────────────────────────────────────────

    @Test
    void a_refresh_that_returns_no_new_refresh_token_keeps_the_old_one() {
        // Dropping it would end the connection at the next expiry, silently.
        server.answers("""
                {"access_token":"at-2","expires_in":3600}""");

        OAuth2UserCreds refreshed = flow.refresh(publicClient(),
                new OAuth2UserCreds("at-1", "rt-1", Instant.now(), List.of(), Map.of()));

        assertThat(refreshed.accessToken()).isEqualTo("at-2");
        assertThat(refreshed.refreshToken()).isEqualTo("rt-1");
        assertThat(server.request(0)).containsEntry("grant_type", "refresh_token")
                .containsEntry("refresh_token", "rt-1");
    }

    @Test
    void a_rotated_refresh_token_replaces_the_old_one() {
        server.answers("""
                {"access_token":"at-2","refresh_token":"rt-2","expires_in":3600}""");

        assertThat(flow.refresh(publicClient(),
                new OAuth2UserCreds("at-1", "rt-1", Instant.now(), List.of(), Map.of())).refreshToken())
                .isEqualTo("rt-2");
    }

    @Test
    void a_connection_without_a_refresh_token_says_so_instead_of_asking() {
        assertThatThrownBy(() -> flow.refresh(publicClient(),
                new OAuth2UserCreds("at-1", null, Instant.now(), List.of(), Map.of())))
                .isInstanceOf(OAuthException.class)
                .hasMessageContaining("Connect it again");
    }

    @Test
    void a_token_is_renewed_before_it_actually_runs_out() {
        Instant now = Instant.parse("2026-09-19T12:00:00Z");

        assertThat(OAuthFlow.needsRefresh(creds(now.plusSeconds(3600)), now)).isFalse();
        assertThat(OAuthFlow.needsRefresh(creds(now.plusSeconds(60)), now)).isTrue();   // inside the margin
        assertThat(OAuthFlow.needsRefresh(creds(now.minusSeconds(1)), now)).isTrue();
        assertThat(OAuthFlow.needsRefresh(null, now)).isFalse();
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private static OAuth2UserCreds creds(Instant expiresAt) {
        return new OAuth2UserCreds("at", "rt", expiresAt, List.of(), Map.of());
    }

    private OAuthProvider publicClient() {
        return provider(null, true);
    }

    private OAuthProvider provider(String clientSecret, boolean pkce) {
        return new OAuthProvider(UUID.randomUUID(), "example", "generic", "the-client-id", clientSecret,
                "https://login.example.com/authorize", server.url(),
                List.of("openid", "offline_access"), pkce,
                Map.of("audience", "api://example"), Map.of());
    }

    private static Map<String, String> queryOf(String url) {
        return FakeTokenEndpoint.split(URI.create(url).getRawQuery());
    }
}
