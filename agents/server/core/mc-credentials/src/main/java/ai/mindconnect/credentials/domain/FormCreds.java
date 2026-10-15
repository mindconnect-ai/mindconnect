package ai.mindconnect.credentials.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The secret half of a connection somebody filled in by hand — a mailbox
 * password, an app-specific password, a PAT that has no OAuth behind it.
 *
 * <p>Keyed by the field name of the provider's {@code Schema}, so a provider
 * with two secrets (a mailbox password and a separate SMTP one) keeps them
 * apart without a second type. Which fields land here and which stay in
 * {@link Connection#settings()} is decided by the schema — {@code
 * Format.PASSWORD} means secret — not by the tool and not by the form.
 *
 * <p>Stored encrypted; see {@code EncryptingConnectionRepository}.
 */
public record FormCreds(Map<String, String> secrets) implements UserCredentials {

    public FormCreds {
        secrets = secrets == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(secrets));
    }

    public static FormCreds of(Map<String, String> secrets) {
        return new FormCreds(secrets);
    }

    /** The secret stored under {@code field}, or null. */
    public String get(String field) {
        return secrets.get(field);
    }

    /** These credentials with every value replaced by {@code mapper} — encrypting, decrypting. */
    public FormCreds mapValues(java.util.function.UnaryOperator<Map<String, String>> mapper) {
        return new FormCreds(mapper.apply(secrets));
    }

    /** Never the values. */
    @Override
    public String toString() {
        return "FormCreds" + secrets.keySet();
    }
}
