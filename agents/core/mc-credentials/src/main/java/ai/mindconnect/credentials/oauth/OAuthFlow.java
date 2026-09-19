package ai.mindconnect.credentials.oauth;

import ai.mindconnect.credentials.domain.OAuth2UserCreds;
import ai.mindconnect.credentials.domain.OAuthProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The authorization-code exchange, on its own: build the URL the browser goes
 * to, turn the code that comes back into tokens, and refresh them when they
 * run out.
 *
 * <p><b>PKCE by default, and no secret where there is none.</b> An
 * installation that shares one app registration with every other installation
 * cannot carry a client secret — whoever has the jar has the secret, which is
 * then not one. The correct shape for that is a public client proving
 * possession of a one-time verifier instead ({@link OAuthProvider#usePkce()}),
 * and this sends the secret only when a provider actually has one, for the
 * operator who registered a confidential client of their own.
 *
 * <p>The JDK's HTTP client on purpose: this is two form posts, and a library
 * module should not pull an HTTP stack in for them.
 */
public class OAuthFlow {

    /** How long to wait for the provider's token endpoint. */
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    /** Refresh this long before the token actually expires, so a call never races the clock. */
    public static final Duration REFRESH_MARGIN = Duration.ofMinutes(2);

    private final HttpClient http;
    private final ObjectMapper json;
    private final SecureRandom random = new SecureRandom();

    public OAuthFlow(ObjectMapper json) {
        this(HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build(),
                json);
    }

    public OAuthFlow(HttpClient http, ObjectMapper json) {
        this.http = Objects.requireNonNull(http, "http");
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * What the browser is sent to, and what has to be remembered until it comes
     * back.
     *
     * @param authorizeUrl where to send them
     * @param state        the one-time value the callback must echo — this is
     *                     what stops somebody else's callback from attaching
     *                     their account to this user
     * @param codeVerifier the PKCE secret, kept here and sent only at exchange;
     *                     null when the provider does not use PKCE
     */
    public record Start(String authorizeUrl, String state, String codeVerifier) { }

    /** Builds the authorization URL for one attempt. */
    public Start start(OAuthProvider provider, String redirectUri, List<String> scopes) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(redirectUri, "redirectUri");
        String state = randomUrlSafe(32);
        String verifier = provider.usePkce() ? randomUrlSafe(64) : null;

        Map<String, String> query = new LinkedHashMap<>();
        query.put("response_type", "code");
        query.put("client_id", provider.clientId());
        query.put("redirect_uri", redirectUri);
        query.put("state", state);
        List<String> wanted = scopes == null || scopes.isEmpty() ? provider.defaultScopes() : scopes;
        if (!wanted.isEmpty()) {
            query.put("scope", String.join(" ", wanted));
        }
        if (verifier != null) {
            query.put("code_challenge", challengeOf(verifier));
            query.put("code_challenge_method", "S256");
        }
        provider.extraAuthzParams().forEach(query::put);

        return new Start(provider.authzUrl() + (provider.authzUrl().contains("?") ? "&" : "?") + form(query),
                state, verifier);
    }

    /**
     * The code from the callback, for tokens.
     *
     * @throws OAuthException when the provider refuses, with the reason it gave
     */
    public OAuth2UserCreds exchange(OAuthProvider provider, String code, String codeVerifier, String redirectUri) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("grant_type", "authorization_code");
        body.put("code", code);
        body.put("redirect_uri", redirectUri);
        if (codeVerifier != null) {
            body.put("code_verifier", codeVerifier);
        }
        return post(provider, body);
    }

    /**
     * A new access token from the refresh token. Providers differ on whether
     * they hand back a new refresh token; when they do not, the old one is
     * kept, because dropping it would end the connection at the next expiry.
     *
     * @throws OAuthException when the refresh is refused — the user has to connect again
     */
    public OAuth2UserCreds refresh(OAuthProvider provider, OAuth2UserCreds stored) {
        if (stored.refreshToken() == null) {
            throw new OAuthException("This connection has no refresh token, so it cannot be renewed. "
                    + "Connect it again.");
        }
        Map<String, String> body = new LinkedHashMap<>();
        body.put("grant_type", "refresh_token");
        body.put("refresh_token", stored.refreshToken());
        OAuth2UserCreds fresh = post(provider, body);
        return fresh.refreshToken() != null
                ? fresh
                : fresh.withRefreshToken(stored.refreshToken());
    }

    /** True when {@code credentials} should be refreshed before the next use. */
    public static boolean needsRefresh(OAuth2UserCreds credentials, Instant now) {
        return credentials != null && credentials.expiresAt() != null
                && !credentials.expiresAt().minus(REFRESH_MARGIN).isAfter(now);
    }

    // ── internals ───────────────────────────────────────────────────────────

    private OAuth2UserCreds post(OAuthProvider provider, Map<String, String> body) {
        Map<String, String> form = new LinkedHashMap<>(body);
        form.put("client_id", provider.clientId());
        // Only where there is one to send: a public client has none, and an
        // empty client_secret is refused by some providers rather than ignored.
        if (provider.clientSecret() != null && !provider.clientSecret().isBlank()) {
            form.put("client_secret", provider.clientSecret());
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(provider.tokenUrl()))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form(form)))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new OAuthException("Could not reach " + provider.name() + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OAuthException("Interrupted while talking to " + provider.name(), e);
        }
        if (response.statusCode() >= 400) {
            throw new OAuthException(provider.name() + " refused: " + describe(response.body()));
        }
        return credentialsFrom(response.body());
    }

    private OAuth2UserCreds credentialsFrom(String body) {
        JsonNode node;
        try {
            node = json.readTree(body);
        } catch (IOException e) {
            throw new OAuthException("The token endpoint answered with something that is not JSON", e);
        }
        String accessToken = text(node, "access_token");
        if (accessToken == null) {
            throw new OAuthException("The token endpoint answered without an access token: " + describe(body));
        }
        long expiresIn = node.path("expires_in").asLong(3600);
        String scope = text(node, "scope");
        return new OAuth2UserCreds(accessToken, text(node, "refresh_token"),
                Instant.now().plusSeconds(expiresIn),
                scope == null ? List.of() : List.of(scope.split("\\s+")),
                Map.of());
    }

    /** The provider's own error, when it sent one — never the whole body, which may carry a token. */
    private String describe(String body) {
        try {
            JsonNode node = json.readTree(body);
            String error = text(node, "error");
            String description = text(node, "error_description");
            if (error != null) {
                return description == null ? error : error + " — " + description;
            }
        } catch (IOException ignored) {
            // then it was not JSON, and the status code is all we have
        }
        return "no reason given";
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String form(Map<String, String> values) {
        List<String> pairs = new ArrayList<>();
        values.forEach((key, value) -> pairs.add(encode(key) + "=" + encode(value)));
        return String.join("&", pairs);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String randomUrlSafe(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    static String challengeOf(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
