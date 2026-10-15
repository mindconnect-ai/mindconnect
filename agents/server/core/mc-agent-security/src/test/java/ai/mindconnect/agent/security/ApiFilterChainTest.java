package ai.mindconnect.agent.security;

import ai.mindconnect.agent.UserId;
import ai.mindconnect.agentrest.auth.CurrentUser;
import ai.mindconnect.agentrest.auth.CurrentUserResolver;
import ai.mindconnect.agentrest.auth.CurrentUserWebConfig;
import ai.mindconnect.agentrest.auth.CurrentUsers;
import ai.mindconnect.user.adapter.memory.InMemoryApiTokenRepository;
import ai.mindconnect.user.adapter.memory.InMemoryUserRepository;
import ai.mindconnect.user.service.ApiTokenService;
import ai.mindconnect.user.service.UserService;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An API filter chain built the way the apps build theirs: a request is
 * answered as the user its API token or its JWT names, and not at all without
 * one — a browser session does not count, and no CSRF token is asked for.
 */
@WebMvcTest(properties = "mindconnect.auth.enabled=true")
@ContextConfiguration(classes = ApiFilterChainTest.TestApp.class)
class ApiFilterChainTest {

    private static final RSAKey SIGNING_KEY = rsaKey();
    private static final RSAKey FOREIGN_KEY = rsaKey();

    @Autowired
    MockMvc mvc;

    @Autowired
    ApiTokenService tokens;

    @Test
    void withoutAuthenticationTheApiAnswers401AndSaysWhatToSend() throws Exception {
        mvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.message").value(ApiErrorResponder.TOKEN_REQUIRED));
    }

    @Test
    void anApiTokenRunsTheRequestAsItsOwner() throws Exception {
        String secret = tokens.issue(UserId.of("alice"), "cli", null).secret();

        mvc.perform(get("/api/me").header("Authorization", "Bearer " + secret))
                .andExpect(status().isOk()).andExpect(content().string("alice"));
        mvc.perform(post("/api/me").header("Authorization", "Bearer " + secret))
                .andExpect(status().isOk()).andExpect(content().string("alice"));
    }

    @Test
    void aRevokedOrForgedApiTokenIsRejected() throws Exception {
        var issued = tokens.issue(UserId.of("alice"), "cli", null);
        tokens.revoke(UserId.of("alice"), issued.token().id());

        mvc.perform(get("/api/me").header("Authorization", "Bearer " + issued.secret()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me").header("Authorization", "Bearer mct_forged"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Invalid, expired or revoked API token"));
    }

    @Test
    void aJwtRunsTheRequestAsTheUserItNames() throws Exception {
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + jwt(SIGNING_KEY, "bob")))
                .andExpect(status().isOk()).andExpect(content().string("bob"));
        mvc.perform(post("/api/me").header("Authorization", "Bearer " + jwt(SIGNING_KEY, "bob")))
                .andExpect(status().isOk());
    }

    @Test
    void aJwtSignedByAnotherKeyOrNamingNobodyIsRejected() throws Exception {
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + jwt(FOREIGN_KEY, "bob")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + jwt(SIGNING_KEY, null)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aWriteWithoutTokenAndSessionAnswers401NotACsrfError() throws Exception {
        mvc.perform(post("/api/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(ApiErrorResponder.TOKEN_REQUIRED));
    }

    @Test
    void aBrowserSessionDoesNotCountForTheApi() throws Exception {
        // A logged-in browser as the secured UI chain leaves it: the context in the HttpSession.
        OidcUser carol = new DefaultOidcUser(List.of(), OidcIdToken.withTokenValue("id-token")
                .subject("sub-carol").claim("preferred_username", "carol")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build());
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(new OAuth2AuthenticationToken(carol, carol.getAuthorities(), "keycloak")));

        mvc.perform(get("/api/me").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(ApiErrorResponder.TOKEN_REQUIRED));
        mvc.perform(post("/api/me").session(session))
                .andExpect(status().isUnauthorized());
    }

    // ── fixture ─────────────────────────────────────────────────────────────

    private static String jwt(RSAKey key, String preferredUsername) {
        var claims = JwtClaimsSet.builder().subject("sub-x").issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
        if (preferredUsername != null) {
            claims.claim("preferred_username", preferredUsername);
        }
        var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).keyId(key.getKeyID()).build(), claims.build())).getTokenValue();
    }

    private static RSAKey rsaKey() {
        try {
            return new RSAKeyGenerator(2048).keyID(java.util.UUID.randomUUID().toString()).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @RestController
    static class Echo {
        @GetMapping("/api/me")
        String me(@CurrentUser UserId user) {
            return user.value();
        }

        @PostMapping("/api/me")
        String write(@CurrentUser UserId user) {
            return user.value();
        }
    }

    @Configuration
    @EnableWebSecurity
    @Import({Echo.class, CurrentUsers.class, CurrentUserWebConfig.class})
    static class TestApp {

        @Bean
        ApiTokenService apiTokenService() {
            return new ApiTokenService(new InMemoryApiTokenRepository());
        }

        @Bean
        UserRecorder userRecorder() {
            return new UserRecorder(new UserService(new InMemoryUserRepository()));
        }

        @Bean
        CurrentUserResolver currentUserResolver() {
            return new SecurityCurrentUserResolver();
        }

        @Bean
        JwtDecoder jwtDecoder() throws Exception {
            return NimbusJwtDecoder.withPublicKey(SIGNING_KEY.toRSAPublicKey()).build();
        }

        @Bean
        ApiAuthentication apiAuthentication(ApiTokenService tokens, UserRecorder recorder, JwtDecoder decoder) {
            return new ApiAuthentication(tokens, recorder, decoder);
        }

        @Bean
        SecurityFilterChain api(HttpSecurity http, ApiAuthentication api) throws Exception {
            api.apply(http.securityMatcher("/api/**")
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated()));
            return http.build();
        }
    }
}
