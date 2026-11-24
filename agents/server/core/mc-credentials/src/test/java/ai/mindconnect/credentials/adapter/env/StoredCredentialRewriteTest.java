package ai.mindconnect.credentials.adapter.env;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.credentials.adapter.memory.InMemoryConnectionRepository;
import ai.mindconnect.credentials.adapter.memory.InMemoryOAuthProviderRepository;
import ai.mindconnect.credentials.domain.ApiKeyCreds;
import ai.mindconnect.credentials.domain.Connection;
import ai.mindconnect.credentials.domain.FormCreds;
import ai.mindconnect.credentials.domain.OAuth2UserCreds;
import ai.mindconnect.credentials.domain.OAuthProvider;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** What an earlier encryption scheme stored is stored in the current one at start; what is current stays as it is. */
class StoredCredentialRewriteTest {

    private static final UserId ALICE = UserId.of("alice");
    private static final Instant NOW = Instant.parse("2026-09-15T12:00:00Z");
    private static final EncryptionHelper ENCRYPTION = new EncryptionHelper("0123456789abcdef");

    /** A stored value as the scheme before AES-GCM wrote it: {@code enc:} and the Base64 of AES/ECB. */
    private static String writtenByTheEarlierScheme(String plain) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec("0123456789abcdef".getBytes(StandardCharsets.UTF_8), "AES"));
        return EncryptionHelper.ENC + Base64.getEncoder().encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
    }

    private static Connection connection(String key, ai.mindconnect.credentials.domain.UserCredentials credentials) {
        return Connection.of(ALICE, "imap", key, key, credentials, Map.of("host", "mail.example.com"), false, NOW);
    }

    private static OAuthProvider provider(String name, String clientSecret) {
        return new OAuthProvider(UUID.randomUUID(), name, "generic", "the-client-id", clientSecret,
                "https://login.example.com/authorize", "https://login.example.com/token",
                List.of("openid"), true, Map.of(), Map.of());
    }

    @Test
    void connections_of_every_shape_are_rewritten_and_a_current_one_is_not_saved_again() throws Exception {
        InMemoryConnectionRepository store = new InMemoryConnectionRepository();
        EncryptingConnectionRepository connections = new EncryptingConnectionRepository(store, ENCRYPTION);
        Connection form = connection("form", FormCreds.of(Map.of("password", writtenByTheEarlierScheme("pw"))));
        Connection apiKey = connection("key", new ApiKeyCreds(writtenByTheEarlierScheme("sk")));
        Connection oauth = connection("oauth", new OAuth2UserCreds(
                writtenByTheEarlierScheme("access"), writtenByTheEarlierScheme("refresh"), NOW, List.of("mail"), Map.of()));
        Connection current = connection("current", new ApiKeyCreds(ENCRYPTION.encryptTagged("sk")));
        List.of(form, apiKey, oauth, current).forEach(store::save);

        assertThat(connections.rewriteEarlierScheme(List.of(ALICE))).isEqualTo(3);

        assertThat(((FormCreds) store.findById(form.id()).orElseThrow().credentials()).get("password")).startsWith("enc:v2:");
        assertThat(((ApiKeyCreds) store.findById(apiKey.id()).orElseThrow().credentials()).value()).startsWith("enc:v2:");
        OAuth2UserCreds storedOauth = (OAuth2UserCreds) store.findById(oauth.id()).orElseThrow().credentials();
        assertThat(storedOauth.accessToken()).startsWith("enc:v2:");
        assertThat(storedOauth.refreshToken()).startsWith("enc:v2:");
        assertThat(store.findById(current.id())).contains(current);
        assertThat(connections.findById(form.id()).orElseThrow().credentials()).isEqualTo(FormCreds.of(Map.of("password", "pw")));
        assertThat(connections.findById(oauth.id()).orElseThrow().credentials())
                .isEqualTo(new OAuth2UserCreds("access", "refresh", NOW, List.of("mail"), Map.of()));
        assertThat(connections.rewriteEarlierScheme(List.of(ALICE))).as("a second start finds nothing to do").isZero();
    }

    @Test
    void a_client_secret_of_the_earlier_scheme_is_rewritten_and_a_public_client_is_left_alone() throws Exception {
        InMemoryOAuthProviderRepository store = new InMemoryOAuthProviderRepository();
        EncryptingOAuthProviderRepository providers = new EncryptingOAuthProviderRepository(store, ENCRYPTION);
        OAuthProvider confidential = provider("confidential", writtenByTheEarlierScheme("the-secret"));
        OAuthProvider open = provider("public", null);
        store.save(confidential);
        store.save(open);

        assertThat(providers.rewriteEarlierScheme()).isEqualTo(1);

        assertThat(store.findById(confidential.id()).orElseThrow().clientSecret()).startsWith("enc:v2:");
        assertThat(providers.findById(confidential.id()).orElseThrow().clientSecret()).isEqualTo("the-secret");
        assertThat(store.findById(open.id())).contains(open);
        assertThat(providers.rewriteEarlierScheme()).as("a second start finds nothing to do").isZero();
    }
}
