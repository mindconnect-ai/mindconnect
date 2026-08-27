package ai.mindconnect.common.util.encryption;

import lombok.RequiredArgsConstructor;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

@RequiredArgsConstructor
public class EncryptionHelper {
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
