package ai.mindconnect.common.util.encryption;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

@RequiredArgsConstructor
public class EncryptionHelper {
    private static final Logger log = LoggerFactory.getLogger(EncryptionHelper.class);
    public static final String PLAIN = "plain:";
    public static final String ENC = "enc:";

    private final String secretKey;

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES";

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
     * Every value {@link #resolve resolved} to plaintext. A value that no longer
     * decrypts — the key was rotated — is left out with a warning naming
     * {@code owner} and the entry, rather than failing everything else the
     * owner stored.
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

    public String encrypt(String input) throws Exception {
        return Base64.getEncoder().encodeToString(doCrypto(Cipher.ENCRYPT_MODE,
                input.getBytes(),
                secretKey.getBytes()
        ));
    }

    public String decrypt(String input) throws Exception {
        byte[] decryptedBytes = doCrypto(
                Cipher.DECRYPT_MODE,
                Base64.getDecoder().decode(input),
                secretKey.getBytes()
        );
        return new String(decryptedBytes);
    }

    private byte[] doCrypto(int cipherMode, byte[] inputBytes, byte[] keyBytes) throws Exception {
        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, ALGORITHM);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(cipherMode, keySpec);
        return cipher.doFinal(inputBytes);
    }
}
