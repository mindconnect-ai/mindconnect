package ai.mindconnect.credentials.oauth;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.credentials.adapter.memory.InMemoryConnectionRepository;
import ai.mindconnect.credentials.adapter.memory.InMemoryOAuthProviderRepository;
import ai.mindconnect.credentials.domain.Connection;
import ai.mindconnect.credentials.domain.ConnectionState;
import ai.mindconnect.credentials.domain.OAuth2UserCreds;
import ai.mindconnect.credentials.domain.OAuthProvider;
import ai.mindconnect.credentials.port.out.ConnectionRepository;
import ai.mindconnect.credentials.port.out.OAuthProviderRepository;
import ai.mindconnect.credentials.service.ConnectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Attaching an account by signing in, and keeping its token alive afterwards. */
class OAuthConnectionsTest {

    private static final UserId ALICE = UserId.of("alice");
    private static final String REDIRECT = "https://mc.example.com/admin/oauth/callback";
    private static final Instant NOW = Instant.parse("2026-09-19T12:00:00Z");

    private FakeTokenEndpoint server;
    private final ConnectionRepository repository = new InMemoryConnectionRepository();
    private final ConnectionService connections = new ConnectionService(repository, fixed());
    private final OAuthProviderRepository providers = new InMemoryOAuthProviderRepository();
    private OAuthConnections oauth;

    @BeforeEach
    void setUp() throws IOException {
        server = new FakeTokenEndpoint();
        providers.save(provider());
        oauth = new OAuthConnections(connections, providers, new OAuthFlow(new ObjectMapper()), fixed());
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void signing_in_attaches_an_account_and_records_which_app_it_came_from() {
        server.answers("""
                {"access_token":"at-1","refresh_token":"rt-1","expires_in":3600}""");

        Connection created = oauth.complete(ALICE, "microsoft", "ms-graph", "Microsoft",
                "the-code", "the-verifier", REDIRECT);

        assertThat(created.provider()).isEqualTo("microsoft");
        assertThat(created.key()).isEqualTo("microsoft");
        assertThat(created.isDefault()).as("the first one is").isTrue();
        assertThat(created.credentials()).isInstanceOf(OAuth2UserCreds.class);
        // Without this a refresh would not know whose token endpoint to ask.
        assertThat(created.settings()).containsEntry(OAuthConnections.PROVIDER_SETTING, "ms-graph");
    }

    @Test
    void a_second_account_of_the_same_app_gets_its_own_key() {
        server.answers("""
                {"access_token":"at-1","expires_in":3600}""");
        server.answers("""
                {"access_token":"at-2","expires_in":3600}""");

        Connection first = oauth.complete(ALICE, "microsoft", "ms-graph", "Microsoft", "c1", "v", REDIRECT);
        Connection second = oauth.complete(ALICE, "microsoft", "ms-graph", "Microsoft", "c2", "v", REDIRECT);

        assertThat(first.key()).isEqualTo("microsoft");
        assertThat(second.key()).isEqualTo("microsoft-2");
        assertThat(second.isDefault()).isFalse();
    }

    @Test
    void an_app_this_installation_does_not_know_is_said_so_before_anything_is_sent() {
        assertThatThrownBy(() -> oauth.begin("nobody", REDIRECT, List.of()))
                .isInstanceOf(OAuthException.class)
                .hasMessageContaining("nobody")
                .hasMessageContaining("administrator");
    }

    @Test
    void a_token_with_time_left_is_handed_back_untouched() {
        Connection stored = attached(NOW.plusSeconds(3600));

        assertThat(oauth.ensureFresh(stored)).isSameAs(stored);
    }

    @Test
    void a_token_about_to_run_out_is_renewed_and_written_back() {
        server.answers("""
                {"access_token":"at-2","expires_in":3600}""");
        Connection stored = attached(NOW.plusSeconds(30));

        Connection fresh = oauth.ensureFresh(stored);

        assertThat(((OAuth2UserCreds) fresh.credentials()).accessToken()).isEqualTo("at-2");
        assertThat(((OAuth2UserCreds) fresh.credentials()).refreshToken())
                .as("the provider sent none back, so the old one is kept").isEqualTo("rt-1");
        // and it is stored, not just returned
        assertThat(repository.findById(stored.id()).orElseThrow().value("x")).isNull();
        assertThat(((OAuth2UserCreds) repository.findById(stored.id()).orElseThrow().credentials())
                .accessToken()).isEqualTo("at-2");
    }

    @Test
    void two_calls_that_find_the_same_token_running_out_refresh_it_once() throws Exception {
        // A provider that rotates refresh tokens: the first refresh gets a new
        // pair, a second one with the old refresh token is refused.
        server.slow(300)
                .answers("""
                        {"access_token":"at-2","refresh_token":"rt-2","expires_in":3600}""")
                .refuses(400, """
                        {"error":"invalid_grant","error_description":"refresh token already used"}""");
        Connection stored = attached(NOW.plusSeconds(30));

        java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(2);
        java.util.concurrent.Callable<Connection> call = () -> {
            start.await();
            return oauth.ensureFresh(stored);
        };
        List<Connection> results;
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = pool.submit(call);
            var second = pool.submit(call);
            results = List.of(first.get(10, java.util.concurrent.TimeUnit.SECONDS),
                    second.get(10, java.util.concurrent.TimeUnit.SECONDS));
        }

        assertThat(server.requests()).as("the second call used the first one's token").isEqualTo(1);
        assertThat(server.request(0)).containsEntry("refresh_token", "rt-1");
        assertThat(results).allSatisfy(result -> {
            assertThat(result.usable()).isTrue();
            assertThat(((OAuth2UserCreds) result.credentials()).accessToken()).isEqualTo("at-2");
        });
        Connection after = repository.findById(stored.id()).orElseThrow();
        assertThat(after.usable()).isTrue();
        assertThat(((OAuth2UserCreds) after.credentials()).refreshToken()).isEqualTo("rt-2");
    }

    @Test
    void a_refresh_does_not_write_over_a_rename_that_landed_in_between() {
        server.answers("""
                {"access_token":"at-2","expires_in":3600}""");
        Connection stored = attached(NOW.plusSeconds(30));
        connections.rename(ALICE, stored.id(), "Work");

        // The caller still holds the record from before the rename.
        Connection fresh = oauth.ensureFresh(stored);

        assertThat(fresh.label()).isEqualTo("Work");
        assertThat(repository.findById(stored.id()).orElseThrow().label()).isEqualTo("Work");
        assertThat(((OAuth2UserCreds) repository.findById(stored.id()).orElseThrow().credentials())
                .accessToken()).isEqualTo("at-2");
    }

    @Test
    void editing_a_signed_in_connection_keeps_its_token_and_its_app_registration() {
        Connection stored = attached(NOW.plusSeconds(3600));

        // What the edit form sends for a provider whose schema has a secret
        // field: the name, a setting and the secret left blank.
        Connection edited = connections.update(ALICE, stored.id(), "Work",
                java.util.Map.of("mailbox", "shared@example.com", "password", ""),
                java.util.Set.of("password")).orElseThrow();

        assertThat(edited.label()).isEqualTo("Work");
        assertThat(edited.credentials()).isEqualTo(stored.credentials());
        assertThat(edited.settings())
                .containsEntry(OAuthConnections.PROVIDER_SETTING, "ms-graph")
                .containsEntry("mailbox", "shared@example.com");
        assertThat(repository.findById(stored.id()).orElseThrow().credentials()).isEqualTo(stored.credentials());
    }

    @Test
    void a_refusal_marks_the_connection_rather_than_failing_every_call_the_same_way() {
        server.refuses(400, """
                {"error":"invalid_grant","error_description":"refresh token expired"}""");
        Connection stored = attached(NOW.plusSeconds(30));

        Connection after = oauth.ensureFresh(stored);

        assertThat(after.usable()).isFalse();
        assertThat(after.state()).isEqualTo(ConnectionState.EXPIRED);
        assertThat(after.stateDetail()).contains("refresh token expired");
        assertThat(repository.findById(stored.id()).orElseThrow().usable()).isFalse();
    }

    @Test
    void a_connection_whose_app_registration_is_gone_says_that_instead() {
        Connection stored = attached(NOW.plusSeconds(30));
        providers.deleteById(providers.findByName("ms-graph").orElseThrow().id());

        Connection after = oauth.ensureFresh(stored);

        assertThat(after.usable()).isFalse();
        assertThat(after.stateDetail()).contains("app registration");
    }

    @Test
    void a_form_filled_connection_is_none_of_this() {
        Connection typed = connections.add(ALICE, "email", "Privat",
                Map.of("host", "imap.example.com", "password", "p"), java.util.Set.of("password"));

        assertThat(oauth.ensureFresh(typed)).isSameAs(typed);
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private Connection attached(Instant expiresAt) {
        return connections.attach(ALICE, "microsoft", "Microsoft",
                new OAuth2UserCreds("at-1", "rt-1", expiresAt, List.of(), Map.of()),
                Map.of(OAuthConnections.PROVIDER_SETTING, "ms-graph"));
    }

    private OAuthProvider provider() {
        return new OAuthProvider(UUID.randomUUID(), "ms-graph", "generic", "the-client-id", null,
                "https://login.example.com/authorize", server.url(), List.of("offline_access"), true,
                Map.of(), Map.of());
    }

    private static Clock fixed() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }
}
