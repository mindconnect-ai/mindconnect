package ai.mindconnect.agent.tool;

import java.util.Objects;

/**
 * What one attempt to use a connection found out — in a sentence the person
 * who typed the password can act on.
 *
 * @param ok      whether the account could be used
 * @param message what happened: "Signed in to imap.web.de; INBOX holds 42
 *                messages", or why not. Never the password.
 */
public record ConnectionTest(boolean ok, String message) {

    public ConnectionTest {
        Objects.requireNonNull(message, "A connection test says what it found");
    }

    public static ConnectionTest ok(String message) {
        return new ConnectionTest(true, message);
    }

    public static ConnectionTest failed(String message) {
        return new ConnectionTest(false, message);
    }
}
