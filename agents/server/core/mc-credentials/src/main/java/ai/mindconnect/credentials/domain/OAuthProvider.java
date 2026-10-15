package ai.mindconnect.credentials.domain;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * CRUD-managed OAuth provider configuration. Mirrored after
 * {@code LlmConfig} — stored as JSON files under
 * {@code {base}/system/oauth-providers/{uuid}.json}, edited via the admin UI,
 * with the {@code clientSecret} encrypted at rest.
 *
 * <p>Two keys matter:
 * <ul>
 *   <li>{@link #name()} — lookup key. {@code McpServerDef.auth.providerName}
 *       references this. Stable across edits.</li>
 *   <li>{@link #kind()} — chooses the {@code OAuthProviderHandler} that knows
 *       this vendor's quirks (Atlassian cloudid, Google refresh behaviour,
 *       GitHub headers, etc.). Values come from
 *       {@code OAuthProviderHandlerRegistry#availableKinds()}.</li>
 * </ul>
 *
 * <p>{@link #extraAuthzParams()} is appended verbatim to the authorization
 * URL (e.g. Atlassian's {@code audience}, Google's {@code access_type}).
 * {@link #additionalParams()} is a free-form bag for handler-specific
 * configuration that doesn't warrant a top-level field — same idea as
 * {@code LlmConfig.additionalParams}.
 */
public record OAuthProvider(
        UUID id,
        String name,
        String kind,
        String clientId,
        String clientSecret,
        String authzUrl,
        String tokenUrl,
        List<String> defaultScopes,
        boolean usePkce,
        Map<String, String> extraAuthzParams,
        Map<String, Object> additionalParams
) {

    public OAuthProvider {
        Objects.requireNonNull(id,        "id");
        Objects.requireNonNull(name,      "name");
        Objects.requireNonNull(kind,      "kind");
        Objects.requireNonNull(clientId,  "clientId");
        Objects.requireNonNull(authzUrl,  "authzUrl");
        Objects.requireNonNull(tokenUrl,  "tokenUrl");
        if (name.isBlank()) throw new IllegalArgumentException("name must not be blank");
        if (kind.isBlank()) throw new IllegalArgumentException("kind must not be blank");
        defaultScopes    = defaultScopes    == null ? List.of() : List.copyOf(defaultScopes);
        extraAuthzParams = extraAuthzParams == null ? Map.of()  : Map.copyOf(extraAuthzParams);
        additionalParams = additionalParams == null ? Map.of()  : Map.copyOf(additionalParams);
    }

    /** Returns a copy with a new {@code clientSecret}; everything else preserved. */
    public OAuthProvider withClientSecret(String newClientSecret) {
        return new OAuthProvider(id, name, kind, clientId, newClientSecret,
                authzUrl, tokenUrl, defaultScopes, usePkce, extraAuthzParams, additionalParams);
    }
}
