package ai.mindconnect.agent.tool;

import ai.mindconnect.schema.Schema;

import java.util.List;
import java.util.Objects;

/**
 * How a user comes to have a connection. Two ways, because there is no OAuth
 * for a mailbox on an arbitrary IMAP server — and one stored result either
 * way, so nothing downstream has to care which was used.
 *
 * <p>A provider may offer both: "Verbinden mit Google" beside "Anderes
 * Postfach (IMAP)" on the same card.
 */
public sealed interface Acquisition {

    /** A short word for the button: "Verbinden", "Zugangsdaten eingeben". */
    String label();

    /**
     * Redirect, consent, callback, token. {@code providerName} names the
     * {@code OAuthProvider} the operator configured — client id, secret and
     * endpoints are the installation's, the token is the user's.
     */
    record OAuth(String providerName, List<String> scopes, String label) implements Acquisition {

        public OAuth {
            Objects.requireNonNull(providerName, "An OAuth acquisition needs a provider name");
            scopes = scopes == null ? List.of() : List.copyOf(scopes);
            if (label == null || label.isBlank()) label = "Connect";
        }

        public static OAuth of(String providerName, String... scopes) {
            return new OAuth(providerName, List.of(scopes), null);
        }
    }

    /**
     * A form the user fills in. The {@link Schema} is the whole declaration:
     * which fields, which are required, which are secret
     * ({@code Format.PASSWORD} — those are encrypted and never shown again),
     * and what the hints say. The Admin UI renders it; no tool ships a screen.
     */
    record Form(Schema schema, String label) implements Acquisition {

        public Form {
            Objects.requireNonNull(schema, "A form acquisition needs a schema");
            if (label == null || label.isBlank()) label = "Add manually";
        }

        public static Form of(Schema schema) {
            return new Form(schema, null);
        }
    }
}
