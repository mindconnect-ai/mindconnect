package ai.mindconnect.agent.tools.virtualenv;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived JWTs that say "this call is for user X in namespace Y", signed by
 * this process for the virtual environment server. The server checks them
 * against {@link #jwks()}, which this process publishes; it never calls back.
 *
 * <p>The key pair lives in memory only and is new on every start. Nothing
 * secret is ever written down, and a token outlives neither its few minutes nor
 * the process that signed it. The server fetches the new public key the first
 * time it sees its key id. More than one instance behind one issuer name would
 * each publish a different key — that setup needs a shared key, which this
 * class does not do.
 */
public class OnBehalfTokens {

    /** Claim naming the namespace the call runs in. */
    public static final String NAMESPACE_CLAIM = "namespace";

    private final String issuer;
    private final String audience;
    private final Duration lifetime;
    private final Clock clock;
    private final RSAKey key;
    private final RSASSASigner signer;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(String token, Instant reuseUntil) {}

    public OnBehalfTokens(String issuer, String audience, Duration lifetime) {
        this(issuer, audience, lifetime, Clock.systemUTC());
    }

    OnBehalfTokens(String issuer, String audience, Duration lifetime, Clock clock) {
        this(issuer, audience, lifetime, clock, newKey());
    }

    private OnBehalfTokens(String issuer, String audience, Duration lifetime, Clock clock, RSAKey key) {
        this.issuer = requireText(issuer, "issuer");
        this.audience = requireText(audience, "audience");
        this.lifetime = Objects.requireNonNull(lifetime, "lifetime");
        if (lifetime.compareTo(Duration.ofSeconds(30)) < 0) {
            throw new IllegalArgumentException("An on-behalf token must live at least 30 seconds");
        }
        this.clock = Objects.requireNonNull(clock, "clock");
        this.key = key;
        try {
            this.signer = new RSASSASigner(key);
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot use the signing key for on-behalf tokens", e);
        }
    }

    /** The same key, signing for another audience at another time — for tests of what a server must refuse. */
    OnBehalfTokens sameKey(String otherAudience, Clock otherClock) {
        return new OnBehalfTokens(issuer, otherAudience, lifetime, otherClock, key);
    }

    private static RSAKey newKey() {
        try {
            return new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot create the signing key for on-behalf tokens", e);
        }
    }

    public String issuer() {
        return issuer;
    }

    /**
     * A token for {@code subject} in {@code namespace}. The same one comes back
     * while more than a third of its lifetime is left, so a burst of tool calls
     * signs once.
     */
    public String token(String subject, String namespace) {
        String sub = requireText(subject, "subject");
        String ns = requireText(namespace, "namespace");
        Instant now = clock.instant();
        Cached cached = cache.get(sub + "\n" + ns);
        if (cached != null && now.isBefore(cached.reuseUntil())) {
            return cached.token();
        }
        Instant expires = now.plus(lifetime);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .subject(sub)
                .claim(NAMESPACE_CLAIM, ns)
                .issueTime(Date.from(now))
                .notBeforeTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(expires))
                .jwtID(UUID.randomUUID().toString())
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot sign an on-behalf token", e);
        }
        String token = jwt.serialize();
        cache.put(sub + "\n" + ns, new Cached(token, expires.minus(lifetime.dividedBy(3))));
        return token;
    }

    /** The public key as a JWK set, the document the server reads from the JWKS endpoint. */
    public Map<String, Object> jwks() {
        return new JWKSet(key.toPublicJWK()).toJSONObject(true);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("An on-behalf token needs a " + name);
        }
        return value.strip();
    }
}
