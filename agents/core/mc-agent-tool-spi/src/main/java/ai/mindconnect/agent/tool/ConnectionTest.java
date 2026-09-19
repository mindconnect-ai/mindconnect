package ai.mindconnect.agent.tool;

import java.util.Objects;
import java.util.Optional;

/**
 * What one attempt to use a connection found out — in a sentence the person
 * who typed the password can act on.
 *
 * @param ok      whether the account could be used
 * @param message what happened: "Signed in to imap.web.de; INBOX holds 42
 *                messages", or why not. Never the password.
 * @param account whom the connection turned out to belong to, when the
 *                source can tell — an e-mail address, a user name. A
 *                connection made by signing in somewhere else is named after
 *                it, so the card says which account it is. Null when unknown.
 */
public record ConnectionTest(boolean ok, String message, String account) {
    public ConnectionTest {
        Objects.requireNonNull(message, "A connection test says what it found");
        account = account == null || account.isBlank() ? null : account.strip();
    }

    public ConnectionTest(boolean ok, String message) {
        this(ok, message, null);
    }

    public static ConnectionTest ok(String message) {
        return new ConnectionTest(true, message, null);
    }

    /** A pass that also says whose account it is. */
    public static ConnectionTest ok(String message, String account) {
        return new ConnectionTest(true, message, account);
    }

    public static ConnectionTest failed(String message) {
        return new ConnectionTest(false, message, null);
    }

    /** The account, when the test named one. */
    public Optional<String> namedAccount() {
        return Optional.ofNullable(account);
    }
}
