package ai.mindconnect.common.util.encryption;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts the secrets an installation stores — LLM keys, connection
 * credentials, namespace and user variables — with AES-GCM and a random nonce
 * per value: two equal secrets do not look equal at rest, and a stored value
 * that was changed is refused instead of decrypting to something else.
 *
 * <p>A stored value is {@code enc:v2:} followed by the Base64 of nonce and
 * ciphertext. The scheme's tag sits behind the {@code enc:} prefix rather than
 * replacing it, so everything that asks "is this encrypted?" with
 * {@link #ENC} keeps its answer, and it ends in a colon because Base64 has
 * none: a value written before the tag existed — {@code enc:} followed
 * directly by the Base64 of AES/ECB — can never be mistaken for a tagged one.
 * Those earlier values are still read, with the same key, and leave the store
 * when they are next saved.
 */
@RequiredArgsConstructor
public class EncryptionHelper {
    private static final Logger log = LoggerFactory.getLogger(EncryptionHelper.class);
    public static final String PLAIN = "plain:";
    public static final String ENC = "enc:";

    private final String secretKey;

    private static final String ALGORITHM = "AES";
    /** What {@code Cipher.getInstance("AES")} resolved to when the untagged values were written. */
    private static final String UNTAGGED_TRANSFORMATION = "AES/ECB/PKCS5Padding";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String V2 = "v2:";
    private static final int NONCE_BYTES = 12;
    private static final int AUTH_TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Returns an instance that resolves {@code plain:} prefixes but cannot decrypt {@code enc:} values. */
    public static EncryptionHelper noEncryption() {
        return new EncryptionHelper(null);
    }


    /**
     * Returns the plaintext value. Accepts {@code plain:…}, {@code enc:…}, or
     * bare plaintext (no prefix) for backwards compatibility with configs saved
     * before encryption was introduced.
     */
    public String resolve(String value) {
        if (value == null) return null;
        if (value.startsWith(ENC)) {
            try {
                return decrypt(value.substring(ENC.length()));
            } catch (Exception e) {
                throw new RuntimeException("Error decrypting value", e);
            }
        }
        if (value.startsWith(PLAIN)) return value.substring(PLAIN.length());
        return value; // bare plaintext — no prefix
    }

    public String getUnencryptedPassword(String password) {
        try {
            if (password.startsWith("plain:")) {
                return password.substring(PLAIN.length()); // Remove "plain:" prefix
            } else if (password.startsWith(ENC)) {
                String encryptedPassword = password.substring(ENC.length()); // Remove "enc:" prefix
                return decrypt(encryptedPassword);
            }
            throw new IllegalArgumentException("Password format is not recognized");
        } catch (Exception e) {
            throw new RuntimeException("Error decrypting password", e);
        }
    }

    /** {@link #encrypt} with the {@code enc:} tag {@link #resolve} expects. */
    public String encryptTagged(String plain) {
        try {
            return ENC + encrypt(plain);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt a value", e);
        }
    }

    /** Every value {@link #encryptTagged encrypted}; the map's order is kept. */
    public Map<String, String> encryptValues(Map<String, String> plain) {
        Map<String, String> encrypted = new LinkedHashMap<>();
        plain.forEach((name, value) -> encrypted.put(name, encryptTagged(value)));
        return encrypted;
    }

    /**
     * {@code plain} encrypted for storage, keeping every entry of {@code stored}
     * that no longer decrypts and that {@code plain} does not name.
     *
     * <p>A value that does not decrypt is left out when a record is read (see
     * {@link #decryptValues}), so a record saved back after any change — a
     * login stamp, another variable — would otherwise lose it for good. With a
     * wrong or rotated key at start-up that deleted every user's variables on
     * their next login, and putting the right key back brought nothing back. The
     * unreadable ciphertext stays as it was until its name is set again.
     */
    public Map<String, String> encryptValuesKeepingUnreadable(Map<String, String> plain, Map<String, String> stored) {
        Map<String, String> out = new LinkedHashMap<>();
        if (stored != null) {
            stored.forEach((name, value) -> {
                if (!plain.containsKey(name) && !decrypts(value)) out.put(name, value);
            });
        }
        out.putAll(encryptValues(plain));
        return out;
    }

    /** Whether {@code stored} reads back with this key — a plain value always does. */
    public boolean decrypts(String stored) {
        try {
            resolve(stored);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Every value {@link #resolve resolved} to plaintext. A value that no longer
     * decrypts — the key was rotated — is left out with a warning naming
     * {@code owner} and the entry, rather than failing everything else the
     * owner stored. A repository that saves such a record back keeps the
     * unreadable value with {@link #encryptValuesKeepingUnreadable}.
     */
    public Map<String, String> decryptValues(Map<String, String> stored, String owner) {
        Map<String, String> plain = new LinkedHashMap<>();
        stored.forEach((name, value) -> {
            try {
                plain.put(name, resolve(value));
            } catch (RuntimeException e) {
                log.warn("Cannot decrypt variable '{}' of {} — skipping it (was the encryption key rotated?)", name, owner);
            }
        });
        return plain;
    }

    /**
     * {@code stored} in the current scheme when it is an {@code enc:} value an
     * earlier scheme wrote and this key reads; anything else as it is. For a
     * repository that hands ciphertexts through unchanged: without this a
     * value written before AES-GCM would stay in the store for as long as
     * its record is only ever saved back.
     */
    public String reencryptEarlierScheme(String stored) {
        if (stored == null || !stored.startsWith(ENC) || stored.startsWith(ENC + V2) || !decrypts(stored)) {
            return stored;
        }
        return encryptTagged(resolve(stored));
    }

    /**
     * The ciphertext without the {@code enc:} prefix, already carrying the
     * scheme's tag: {@code ENC + encrypt(…)} is a complete stored value. A
     * fresh nonce per call, so encrypting the same input twice gives two
     * different results — compare plaintexts, never ciphertexts.
     */
    public String encrypt(String input) throws Exception {
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(AUTH_TAG_BITS, nonce));
        byte[] encrypted = cipher.doFinal(input.getBytes(StandardCharsets.UTF_8));
        byte[] stored = new byte[NONCE_BYTES + encrypted.length];
        System.arraycopy(nonce, 0, stored, 0, NONCE_BYTES);
        System.arraycopy(encrypted, 0, stored, NONCE_BYTES, encrypted.length);
        return V2 + Base64.getEncoder().encodeToString(stored);
    }

    /** Reads what {@link #encrypt} returns, and the untagged Base64 the earlier AES/ECB scheme wrote. */
    public String decrypt(String input) throws Exception {
        if (!input.startsWith(V2)) return decryptUntagged(input);
        byte[] stored = Base64.getDecoder().decode(input.substring(V2.length()));
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(AUTH_TAG_BITS, stored, 0, NONCE_BYTES));
        return new String(cipher.doFinal(stored, NONCE_BYTES, stored.length - NONCE_BYTES), StandardCharsets.UTF_8);
    }

    private String decryptUntagged(String input) throws Exception {
        Cipher cipher = Cipher.getInstance(UNTAGGED_TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, key());
        return new String(cipher.doFinal(Base64.getDecoder().decode(input)));
    }

    private SecretKeySpec key() {
        return new SecretKeySpec(secretKey.getBytes(), ALGORITHM);
    }
}
