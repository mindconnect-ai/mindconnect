package ai.mindconnect.mail;

import java.util.Objects;

/**
 * One mailbox the signed-in user has attached, as the account switcher shows
 * it.
 *
 * <p>Three providers, one list. The point of the client is that "my mail"
 * means every account a person has connected, and not one of them at a time —
 * so what the switcher shows is the union of the {@code email},
 * {@code microsoft} and {@code google} connections, each still knowing which
 * it came from.
 *
 * @param provider {@code email}, {@code microsoft} or {@code google} — the
 *                 connection provider, which decides the {@link MailStore}
 * @param key      the connection's stable key within that provider
 * @param label    what the user called it
 * @param usable   false when the connection says it is broken — an expired
 *                 consent, a changed password. It stays in the list and says
 *                 so; dropping it would only make it mysterious
 */
public record ConnectedMailbox(String provider, String key, String label, boolean usable) {

    public ConnectedMailbox {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(key, "key");
        if (label == null || label.isBlank()) label = key;
    }

    /**
     * What a URL carries. A connection key is lowercase letters, digits,
     * {@code -} and {@code _}, so a dot separates the two halves without
     * needing to be escaped or parsed carefully.
     */
    public String id() {
        return provider + "." + key;
    }

    public static String providerOf(String id) {
        int dot = id == null ? -1 : id.indexOf('.');
        return dot < 0 ? null : id.substring(0, dot);
    }

    public static String keyOf(String id) {
        int dot = id == null ? -1 : id.indexOf('.');
        return dot < 0 ? null : id.substring(dot + 1);
    }

    /** For the account switcher: "Arbeit · Outlook". */
    public String describe() {
        return label + " · " + kind();
    }

    /** What the mailbox is: "Outlook", "Gmail", "IMAP". */
    public String kind() {
        return switch (provider) {
            case "microsoft" -> "Outlook";
            case "google" -> "Gmail";
            default -> "IMAP";
        };
    }
}
