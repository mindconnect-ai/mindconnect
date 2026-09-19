package ai.mindconnect.credentials.oauth;

import ai.mindconnect.credentials.adapter.memory.InMemoryOAuthProviderRepository;
import ai.mindconnect.credentials.domain.OAuthProvider;
import ai.mindconnect.credentials.port.out.OAuthProviderRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** What a module brings with it, and what an operator's own decision does to it. */
class OAuthProviderContributionsTest {

    private final OAuthProviderRepository providers = new InMemoryOAuthProviderRepository();

    @Test
    void a_module_brings_its_registration_and_it_is_stored_once() {
        assertThat(install(contribution(null))).containsExactly("ms-graph");

        // A second start changes nothing — and must not, or an operator's edit
        // would be undone by every restart.
        assertThat(install(contribution(null))).isEmpty();
        assertThat(providers.findAll()).hasSize(1);
    }

    @Test
    void an_operators_own_client_id_replaces_the_shipped_one_and_nothing_else() {
        install(contribution("their-own-client-id"));

        OAuthProvider stored = providers.findByName("ms-graph").orElseThrow();
        assertThat(stored.clientId()).isEqualTo("their-own-client-id");
        assertThat(stored.authzUrl()).isEqualTo("https://login.example.com/authorize");
        assertThat(stored.defaultScopes()).containsExactly("offline_access");
        assertThat(stored.usePkce()).isTrue();
    }

    @Test
    void a_registration_the_operator_already_has_is_left_alone() {
        OAuthProvider theirs = new OAuthProvider(UUID.randomUUID(), "ms-graph", "microsoft",
                "their-client", "their-secret", "https://their.example.com/authorize",
                "https://their.example.com/token", List.of("Mail.Read"), false, Map.of(), Map.of());
        providers.save(theirs);

        assertThat(install(contribution(null))).isEmpty();
        assertThat(providers.findByName("ms-graph").orElseThrow()).isEqualTo(theirs);
    }

    @Test
    void a_shipped_client_secret_is_dropped_rather_than_stored() {
        // A registration that travels with a jar is a public client: whoever
        // has the jar has whatever it carries.
        OAuthProviderContribution careless = () -> new OAuthProvider(UUID.randomUUID(), "careless", "x",
                "client", "not-a-secret-any-more", "https://a/authorize", "https://a/token",
                List.of(), true, Map.of(), Map.of());

        install(careless);

        assertThat(providers.findByName("careless").orElseThrow().clientSecret()).isNull();
    }

    @Test
    void one_broken_contribution_does_not_take_the_others_down() {
        OAuthProviderContribution broken = () -> {
            throw new IllegalStateException("no client id configured");
        };

        assertThat(install(broken, contribution(null))).containsExactly("ms-graph");
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private List<String> install(OAuthProviderContribution... contributions) {
        return new OAuthProviderContributions(providers, List.of(contributions)).install();
    }

    private static OAuthProviderContribution contribution(String operatorClientId) {
        return new OAuthProviderContribution() {
            @Override public OAuthProvider provider() {
                return new OAuthProvider(UUID.randomUUID(), "ms-graph", "microsoft", "shipped-client-id",
                        null, "https://login.example.com/authorize", "https://login.example.com/token",
                        List.of("offline_access"), true, Map.of(), Map.of());
            }
            @Override public String clientId() { return operatorClientId; }
        };
    }
}
