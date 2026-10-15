package ai.mindconnect.credentials.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * OAuth2 access + refresh token pair plus issuance metadata.
 *
 * <p>{@code metadata} is a free-form bag for vendor-specific post-auth
 * enrichment, e.g. Atlassian's {@code cloudid} or the tenant id resolved
 * after the initial token exchange. The {@code OAuthProviderHandler#enrich}
 * hook is the canonical place to populate it.
 *
 * <p>{@code refreshToken} may be {@code null} when the provider does not
 * support refresh (e.g. some classic GitHub OAuth flows).
 */
public record OAuth2UserCreds(
        String accessToken,
        String refreshToken,
        Instant expiresAt,
        List<String> scopes,
        Map<String, String> metadata
) implements UserCredentials {

    public OAuth2UserCreds {
        Objects.requireNonNull(accessToken, "accessToken");
        Objects.requireNonNull(expiresAt,   "expiresAt");
        scopes   = scopes   == null ? List.of() : List.copyOf(scopes);
        metadata = metadata == null ? Map.of()  : Map.copyOf(metadata);
    }

    /** Returns a copy with a new {@code refreshToken}, preserving everything else. */
    public OAuth2UserCreds withRefreshToken(String newRefreshToken) {
        return new OAuth2UserCreds(accessToken, newRefreshToken, expiresAt, scopes, metadata);
    }

    /** Returns a copy with merged metadata (new entries win on key collisions). */
    public OAuth2UserCreds withMetadata(Map<String, String> extra) {
        if (extra == null || extra.isEmpty()) return this;
        var merged = new java.util.HashMap<>(metadata);
        merged.putAll(extra);
        return new OAuth2UserCreds(accessToken, refreshToken, expiresAt, scopes, Map.copyOf(merged));
    }
}
