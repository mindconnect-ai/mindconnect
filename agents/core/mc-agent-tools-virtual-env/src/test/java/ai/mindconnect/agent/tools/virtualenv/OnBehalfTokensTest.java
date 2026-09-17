package ai.mindconnect.agent.tools.virtualenv;

import ai.mindconnect.agent.AgentId;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.Scope;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.SessionId;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.agent.tool.ToolCallScope;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a virtual environment server checks, checked here the same way: the signature
 * against the published key set, and the claims it reads the caller from.
 */
class OnBehalfTokensTest {

    private final OnBehalfTokens tokens = new OnBehalfTokens("mindconnect-agents", "mc-virtual-env", Duration.ofMinutes(5));

    @Test
    void a_token_verifies_against_the_published_keys_and_names_user_namespace_and_audience() throws Exception {
        SignedJWT jwt = SignedJWT.parse(tokens.token("david", "team-a"));
        RSAKey published = JWKSet.parse(tokens.jwks()).getKeyByKeyId(jwt.getHeader().getKeyID()).toRSAKey();

        assertThat(published.isPrivate()).as("only the public key is published").isFalse();
        assertThat(jwt.verify(new RSASSAVerifier(published))).isTrue();
        var claims = jwt.getJWTClaimsSet();
        assertThat(claims.getIssuer()).isEqualTo("mindconnect-agents");
        assertThat(claims.getAudience()).containsExactly("mc-virtual-env");
        assertThat(claims.getSubject()).isEqualTo("david");
        assertThat(claims.getStringClaim(OnBehalfTokens.NAMESPACE_CLAIM)).isEqualTo("team-a");
        assertThat(Duration.between(claims.getIssueTime().toInstant(), claims.getExpirationTime().toInstant()))
                .isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void another_instance_signs_with_a_key_the_first_one_never_published() throws Exception {
        SignedJWT foreign = SignedJWT.parse(new OnBehalfTokens("mindconnect-agents", "mc-virtual-env",
                Duration.ofMinutes(5)).token("david", "team-a"));

        assertThat(JWKSet.parse(tokens.jwks()).getKeyByKeyId(foreign.getHeader().getKeyID())).isNull();
    }

    @Test
    void a_burst_of_calls_reuses_one_token_per_user() {
        OnBehalfTokens atStart = tokens.sameKey("mc-virtual-env",
                Clock.fixed(Instant.parse("2026-09-17T10:00:00Z"), ZoneOffset.UTC));
        String first = atStart.token("david", "team-a");

        assertThat(atStart.token("david", "team-a")).isEqualTo(first);
        assertThat(atStart.token("eve", "team-a")).isNotEqualTo(first);
    }

    @Test
    void the_source_signs_for_the_calls_user_in_the_current_namespace_and_for_the_installation_without_one()
            throws Exception {
        OnBehalfTokenSource source = new OnBehalfTokenSource(tokens,
                ScopeSupplier.fixed(new Scope(new Namespace("team-a"), null, Map.of())));
        ToolCallScope david = new ToolCallScope(UserId.of("david"), SessionId.of("s1"), AgentId.of("a1"),
                SessionId.of("s1"), null, List.of());

        assertThat(SignedJWT.parse(source.token(david).orElseThrow()).getJWTClaimsSet().getSubject()).isEqualTo("david");
        var nobody = SignedJWT.parse(source.token(ToolCallScope.detached(null)).orElseThrow()).getJWTClaimsSet();
        assertThat(nobody.getSubject()).isEqualTo(OnBehalfTokenSource.INSTALLATION);
        assertThat(nobody.getStringClaim(OnBehalfTokens.NAMESPACE_CLAIM)).isEqualTo("team-a");
    }
}
