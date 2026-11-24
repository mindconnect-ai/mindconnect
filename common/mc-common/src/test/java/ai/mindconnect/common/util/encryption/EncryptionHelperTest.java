package ai.mindconnect.common.util.encryption;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EncryptionHelperTest {

    private static final String KEY = "0123456789abcdef";
    private static final EncryptionHelper ENCRYPTION = new EncryptionHelper(KEY);

    /** A stored value as the scheme before AES-GCM wrote it: {@code enc:} and the Base64 of AES/ECB. */
    private static String writtenByTheEarlierScheme(String plain) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "AES"));
        return EncryptionHelper.ENC + Base64.getEncoder().encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
    }

    private static String withOneCharacterChanged(String stored) {
        int at = stored.length() - 6;
        char other = stored.charAt(at) == 'A' ? 'B' : 'A';
        return stored.substring(0, at) + other + stored.substring(at + 1);
    }

    @Test
    void an_encrypted_value_reads_back_as_the_plaintext() {
        String stored = ENCRYPTION.encryptTagged("sk-secret-äöü");

        assertThat(stored).startsWith("enc:v2:").doesNotContain("sk-secret");
        assertThat(ENCRYPTION.resolve(stored)).isEqualTo("sk-secret-äöü");
        assertThat(ENCRYPTION.getUnencryptedPassword(stored)).isEqualTo("sk-secret-äöü");
    }

    @Test
    void the_prefix_and_the_bare_ciphertext_make_a_stored_value() throws Exception {
        assertThat(ENCRYPTION.resolve(EncryptionHelper.ENC + ENCRYPTION.encrypt("sk-secret"))).isEqualTo("sk-secret");
    }

    @Test
    void every_supported_key_length_encrypts() {
        for (String key : new String[] {KEY, KEY + "01234567", KEY + KEY}) {
            EncryptionHelper encryption = new EncryptionHelper(key);
            assertThat(encryption.resolve(encryption.encryptTagged("v"))).isEqualTo("v");
        }
    }

    @Test
    void two_encryptions_of_one_plaintext_differ() {
        String first = ENCRYPTION.encryptTagged("the same sixteen bytes and more");
        String second = ENCRYPTION.encryptTagged("the same sixteen bytes and more");

        assertThat(first).isNotEqualTo(second);
        assertThat(first.substring(0, 20)).isNotEqualTo(second.substring(0, 20));
    }

    @Test
    void a_tampered_value_is_refused() {
        String tampered = withOneCharacterChanged(ENCRYPTION.encryptTagged("sk-secret"));

        assertThat(ENCRYPTION.decrypts(tampered)).isFalse();
        assertThatThrownBy(() -> ENCRYPTION.resolve(tampered)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void a_value_of_another_key_is_refused() {
        String stored = new EncryptionHelper("fedcba9876543210").encryptTagged("sk-secret");

        assertThat(ENCRYPTION.decrypts(stored)).isFalse();
    }

    @Test
    void a_value_written_by_the_earlier_scheme_is_still_read() throws Exception {
        String earlier = writtenByTheEarlierScheme("sk-secret");

        assertThat(ENCRYPTION.resolve(earlier)).isEqualTo("sk-secret");
        assertThat(ENCRYPTION.getUnencryptedPassword(earlier)).isEqualTo("sk-secret");
        assertThat(ENCRYPTION.decryptValues(Map.of("KEY", earlier), "test")).isEqualTo(Map.of("KEY", "sk-secret"));
    }

    @Test
    void a_value_of_the_earlier_scheme_is_rewritten_in_the_current_one() throws Exception {
        String rewritten = ENCRYPTION.reencryptEarlierScheme(writtenByTheEarlierScheme("sk-secret"));

        assertThat(rewritten).startsWith("enc:v2:");
        assertThat(ENCRYPTION.resolve(rewritten)).isEqualTo("sk-secret");
    }

    @Test
    void rewriting_leaves_alone_what_is_current_plain_or_unreadable() throws Exception {
        String current = ENCRYPTION.encryptTagged("sk-secret");
        String ofAnotherKey = new EncryptionHelper("fedcba9876543210").reencryptEarlierScheme(writtenByTheEarlierScheme("sk-secret"));
        String unreadable = EncryptionHelper.ENC + "somebase64==";

        assertThat(ENCRYPTION.reencryptEarlierScheme(current)).isEqualTo(current);
        assertThat(ENCRYPTION.reencryptEarlierScheme(unreadable)).isEqualTo(unreadable);
        assertThat(ENCRYPTION.reencryptEarlierScheme("plain:sk")).isEqualTo("plain:sk");
        assertThat(ENCRYPTION.reencryptEarlierScheme("sk-bare")).isEqualTo("sk-bare");
        assertThat(ENCRYPTION.reencryptEarlierScheme(null)).isNull();
        assertThat(ofAnotherKey).isEqualTo(writtenByTheEarlierScheme("sk-secret"));
    }

    @Test
    void saving_values_back_keeps_an_unreadable_one_and_rewrites_the_rest() throws Exception {
        String stale = new EncryptionHelper("fedcba9876543210").encryptTagged("w");
        Map<String, String> stored = Map.of("STALE", stale, "GOOD", writtenByTheEarlierScheme("v"));

        Map<String, String> plain = ENCRYPTION.decryptValues(stored, "test");
        Map<String, String> saved = ENCRYPTION.encryptValuesKeepingUnreadable(plain, stored);

        assertThat(plain).isEqualTo(Map.of("GOOD", "v"));
        assertThat(saved.get("STALE")).isEqualTo(stale);
        assertThat(saved.get("GOOD")).startsWith("enc:v2:");
    }

    @Test
    void without_a_key_plain_values_resolve_and_encrypted_ones_are_refused() {
        EncryptionHelper none = EncryptionHelper.noEncryption();

        assertThat(none.resolve("plain:sk")).isEqualTo("sk");
        assertThat(none.resolve("sk-bare")).isEqualTo("sk-bare");
        assertThat(none.resolve(null)).isNull();
        assertThat(none.decrypts(ENCRYPTION.encryptTagged("sk"))).isFalse();
        assertThatThrownBy(() -> none.resolve(ENCRYPTION.encryptTagged("sk"))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> none.encryptTagged("sk")).isInstanceOf(IllegalStateException.class);
    }
}
