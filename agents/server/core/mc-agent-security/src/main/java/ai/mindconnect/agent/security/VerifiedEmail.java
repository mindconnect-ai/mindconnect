package ai.mindconnect.agent.security;

import java.util.Map;

/**
 * The address a sign-in may be recorded under. Namespaces list people by
 * e-mail address, so the address a token carries decides what its bearer may
 * do — and at an identity provider that lets people edit their own address, a
 * token can carry anybody's.
 *
 * <p>So an address counts only when the provider vouches for it: a token that
 * says {@code email_verified: false} records no address at all, and the one
 * stored from an earlier, verified sign-in stays (a null detail keeps the
 * stored value, see {@code UserService.recordLogin}). A token without the
 * claim is taken at its word, because some providers never send it and their
 * users would otherwise lose every namespace they are in; a provider that lets
 * people change their address unchecked has to send the claim.
 */
final class VerifiedEmail {

    /** The standard OpenID Connect claim. */
    static final String CLAIM = "email_verified";

    private VerifiedEmail() { }

    /**
     * {@code email} when the claims do not say it is unverified, otherwise null.
     *
     * @param claims the token's claims; null counts as a token without the claim
     */
    static String of(String email, Map<String, Object> claims) {
        if (email == null || email.isBlank()) return null;
        Object verified = claims == null ? null : claims.get(CLAIM);
        if (verified == null) return email;
        if (verified instanceof Boolean flag) return flag ? email : null;
        return "true".equalsIgnoreCase(String.valueOf(verified).strip()) ? email : null;
    }
}
