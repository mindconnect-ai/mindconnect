package ai.mindconnect.user.service;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.user.domain.ApiToken;
import ai.mindconnect.user.domain.ApiTokenId;
import ai.mindconnect.user.port.out.ApiTokenRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Issues, checks and revokes personal API tokens.
 *
 * <p>A secret is {@value #PREFIX} followed by 32 random bytes, base64url. With
 * that much entropy a plain SHA-256 is the right stored form: nobody can guess
 * a secret from its hash, and a request is authenticated by one lookup of the
 * hash — a slow password hash would buy nothing and cost every API call. The
 * secret leaves this class exactly once, in {@link Issued}.
 */
public class ApiTokenService {

    /** Every secret starts with this, so a request can tell an API token from a JWT at a glance. */
    public static final String PREFIX = "mct_";

    /** {@link ApiToken#lastUsedAt()} is written at most this often per token. */
    public static final Duration USE_RESOLUTION = Duration.ofMinutes(1);

    /** The longest name a token may have. */
    public static final int MAX_NAME_LENGTH = 100;

    private static final int SECRET_BYTES = 32;
    private static final int HINT_LENGTH = PREFIX.length() + 6;

    private final ApiTokenRepository tokens;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    /** A freshly issued token and its secret — the only time the secret is available. */
    public record Issued(ApiToken token, String secret) {}

    public ApiTokenService(ApiTokenRepository tokens) {
        this(tokens, Clock.systemUTC());
    }

    public ApiTokenService(ApiTokenRepository tokens, Clock clock) {
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Issues a token for {@code owner}.
     *
     * @param name      what the owner calls it; required, at most {@value #MAX_NAME_LENGTH} characters
     * @param expiresAt when it stops working; null for never, otherwise in the future
     * @throws IllegalArgumentException for a blank or overlong name or an expiry that has passed
     */
    public Issued issue(UserId owner, String name, Instant expiresAt) {
        Objects.requireNonNull(owner, "owner");
        String label = name == null ? "" : name.strip();
        if (label.isEmpty()) {
            throw new IllegalArgumentException("A token needs a name");
        }
        if (label.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("A token name has at most " + MAX_NAME_LENGTH + " characters");
        }
        Instant now = clock.instant();
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("A token cannot expire in the past");
        }
        byte[] bytes = new byte[SECRET_BYTES];
        random.nextBytes(bytes);
        String secret = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        ApiToken token = new ApiToken(ApiTokenId.random(), owner, label, hash(secret),
                secret.substring(0, HINT_LENGTH), now, expiresAt, null);
        tokens.save(token);
        return new Issued(token, secret);
    }

    /** The owner's tokens, newest first. */
    public List<ApiToken> list(UserId owner) {
        return tokens.findByUser(owner).stream()
                .sorted(Comparator.comparing(ApiToken::createdAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    /**
     * Revokes one of the owner's tokens.
     *
     * @return false when there is no such token or it belongs to someone else — the two
     *         are deliberately indistinguishable to the caller
     */
    public boolean revoke(UserId owner, ApiTokenId id) {
        Optional<ApiToken> token = tokens.findById(id);
        if (token.isEmpty() || !token.get().userId().equals(owner)) {
            return false;
        }
        tokens.deleteById(id);
        return true;
    }

    /**
     * The token a secret belongs to, if the secret is one of ours and has not
     * expired. Records the use, at most once per {@link #USE_RESOLUTION}.
     */
    public Optional<ApiToken> authenticate(String secret) {
        if (!looksLikeApiToken(secret)) {
            return Optional.empty();
        }
        String hash = hash(secret);
        Optional<ApiToken> found = tokens.findByHash(hash)
                .filter(t -> MessageDigest.isEqual(
                        t.tokenHash().getBytes(StandardCharsets.US_ASCII), hash.getBytes(StandardCharsets.US_ASCII)));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        ApiToken token = found.get();
        if (token.expiredAt(now)) {
            return Optional.empty();
        }
        if (token.lastUsedAt() == null
                || Duration.between(token.lastUsedAt(), now).compareTo(USE_RESOLUTION) >= 0) {
            tokens.recordUse(token.id(), now);
            token = token.usedAt(now);
        }
        return Optional.of(token);
    }

    /** Whether {@code value} has the shape of one of our secrets — not whether it is valid. */
    public static boolean looksLikeApiToken(String value) {
        return value != null && value.startsWith(PREFIX) && value.length() > PREFIX.length();
    }

    /** Hex SHA-256 of a secret, the form tokens are stored and looked up by. */
    public static String hash(String secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
