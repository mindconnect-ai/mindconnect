package ai.mindconnect.agent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;
import java.util.Optional;

/**
 * An e-mail address, as an installation writes people down: namespaces list
 * their admins and users by address, because an address exists before an
 * account does — somebody can be listed before they have ever signed in.
 *
 * <p>Kept lower-case, so that {@code David@Example.com} and
 * {@code david@example.com} are one person, and compared as it is kept. In
 * JSON it is the string itself ({@link JsonValue}), so a list of addresses
 * reads as {@code ["david@example.com"]} and nothing about the storage format
 * gives away that this is a type.
 *
 * <p><strong>The type does not check the shape yet</strong> — see the TODO
 * below. Only blank is refused, so whatever an identity provider or an older
 * document carries is readable. Where a real address is asked for, the check
 * is made at that boundary with {@link #isAddress}, which is the shallow one
 * this type would apply: one {@code @} with something either side, no spaces.
 * What decides whether an address can actually sign in is the identity
 * provider, not this.
 */
public record Email(@JsonValue String value) {

    public Email {
        value = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("An e-mail address must not be blank");
        }
        // TODO validate the shape here once every place that writes an address
        //  goes through a form that checks it: an installation's stored documents
        //  and an identity provider's claims both reach this constructor, and a
        //  record that cannot be read is worse than a value that is not an
        //  address. Until then the check lives at the boundaries (isAddress).
        // if (!looksLikeAddress(value)) {
        //     throw new IllegalArgumentException("'" + value + "' is not an e-mail address");
        // }
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static Email of(String value) {
        return new Email(value);
    }

    /**
     * The address, or empty when there is nothing to read — for what somebody
     * else wrote: a configuration file, a stored document, a claim in a token.
     */
    public static Optional<Email> parse(String value) {
        try {
            return Optional.of(of(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public static boolean isAddress(String value) {
        return value != null && looksLikeAddress(value.strip().toLowerCase(Locale.ROOT));
    }

    /**
     * {@code raw} as an address, with {@code defaultDomain} appended when it is a
     * bare name: {@code david} in an installation whose domain is
     * {@code erni.mindconnect.ai} is {@code david@erni.mindconnect.ai}. An entry
     * that already carries an {@code @} is taken as it is, so a guest from
     * another company is invited by their own address.
     *
     * <p>Which domain that is belongs to the installation, not to this type —
     * the caller passes it in (a brand's, or the server's). Without one a bare
     * name is refused, because writing down something that can never match
     * anybody helps nobody.
     *
     * @throws IllegalArgumentException when {@code raw} is blank, or is a bare name
     *                                  and no domain was given
     */
    public static Email qualified(String raw, String defaultDomain) {
        String value = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("An e-mail address must not be blank");
        }
        if (value.indexOf('@') >= 0) return of(value);
        String domain = defaultDomain == null ? "" : defaultDomain.strip().toLowerCase(Locale.ROOT);
        domain = domain.startsWith("@") ? domain.substring(1) : domain;
        if (domain.isEmpty()) {
            throw new IllegalArgumentException("'" + value + "' is not an e-mail address,"
                    + " and this installation has no default domain to add one");
        }
        return of(value + "@" + domain);
    }

    private static boolean looksLikeAddress(String value) {
        int at = value.indexOf('@');
        return at > 0
                && at == value.lastIndexOf('@')
                && at < value.length() - 1
                && value.chars().noneMatch(Character::isWhitespace);
    }

    @Override
    public String toString() {
        return value;
    }
}
