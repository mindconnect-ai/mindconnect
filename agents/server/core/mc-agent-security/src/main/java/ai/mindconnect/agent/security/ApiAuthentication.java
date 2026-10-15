package ai.mindconnect.agent.security;

import ai.mindconnect.user.service.ApiTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoderInitializationException;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;

import java.util.List;
import java.util.Objects;

/**
 * Bearer authentication for the REST API, in one piece an app adds to its
 * API filter chain with {@link #apply}: a bearer token is either one of our
 * personal API tokens ({@code mct_…}) or a JWT of the identity provider. Either
 * way the request runs as a user, and the user record is kept current.
 *
 * <p>The token's shape decides which of the two checks it gets, and it gets
 * only that one. A {@code ProviderManager} would hand a rejected API token on
 * to the JWT check — which then contacts the identity provider for a string
 * that was never a JWT, and turns an unreachable provider into a failed
 * request instead of a rejected token.
 */
public class ApiAuthentication {

    private static final Logger log = LoggerFactory.getLogger(ApiAuthentication.class);

    private final AuthenticationManager manager;
    private final UserRecorder recorder;

    public ApiAuthentication(ApiTokenService tokens, UserRecorder recorder, JwtDecoder jwtDecoder) {
        this.recorder = Objects.requireNonNull(recorder, "recorder");
        ApiTokenAuthenticationProvider apiTokens = new ApiTokenAuthenticationProvider(tokens, recorder);
        JwtAuthenticationProvider jwt = new JwtAuthenticationProvider(Objects.requireNonNull(jwtDecoder, "jwtDecoder"));
        jwt.setJwtAuthenticationConverter(new JwtUserConverter(recorder));
        this.manager = authentication -> {
            String token = ((BearerTokenAuthenticationToken) authentication).getToken();
            return ApiTokenService.looksLikeApiToken(token)
                    ? apiTokens.authenticate(authentication)
                    : jwt.authenticate(authentication);
        };
    }

    /**
     * Makes a filter chain a bearer-only API: a request authenticates with a
     * token or not at all. A browser session does not count there, so a
     * foreign site has no cookie to ride on and the chain needs no CSRF token.
     * A request the chain turns away gets 401 or 403 with a JSON body
     * ({@link ApiErrorResponder}).
     */
    public HttpSecurity apply(HttpSecurity http) throws Exception {
        ApiErrorResponder errors = new ApiErrorResponder();
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(rs -> rs
                        .authenticationManagerResolver(request -> manager)
                        .authenticationEntryPoint(errors)
                        .accessDeniedHandler(errors))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(errors)
                        .accessDeniedHandler(errors));
    }

    public AuthenticationManager authenticationManager() {
        return manager;
    }

    public UserRecorder recorder() {
        return recorder;
    }

    /**
     * A decoder for JWTs of {@code issuerUri}: signature against the issuer's
     * published keys, issuer and lifetime checked, and — when
     * {@code audiences} names any — an audience among them.
     *
     * <p>The issuer's metadata is fetched on the first token, not at startup,
     * so the app starts while the identity provider is still coming up. While
     * the provider cannot be reached a JWT is rejected (401, and a warning in
     * the log) and the next token tries again; API tokens are not affected.
     * Without an issuer every JWT is rejected.
     */
    public static JwtDecoder jwtDecoder(String issuerUri, List<String> audiences) {
        if (issuerUri == null || issuerUri.isBlank()) {
            return token -> {
                throw new BadJwtException("No JWT issuer is configured (mindconnect.auth.jwt.issuer-uri)");
            };
        }
        List<String> accepted = audiences == null ? List.of()
                : audiences.stream().map(String::strip).filter(a -> !a.isEmpty()).toList();
        JwtDecoder lazy = new SupplierJwtDecoder(() -> {
            NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuerUri);
            OAuth2TokenValidator<Jwt> validator = JwtValidators.createDefaultWithIssuer(issuerUri);
            if (!accepted.isEmpty()) {
                validator = new DelegatingOAuth2TokenValidator<>(validator, audience(accepted));
            }
            decoder.setJwtValidator(validator);
            return decoder;
        });
        return token -> {
            try {
                return lazy.decode(token);
            } catch (JwtDecoderInitializationException e) {
                log.warn("Rejecting a bearer JWT: the issuer {} cannot be reached ({})", issuerUri, rootCause(e));
                throw new BadJwtException("The token issuer cannot be reached", e);
            }
        };
    }

    private static OAuth2TokenValidator<Jwt> audience(List<String> accepted) {
        return jwt -> jwt.getAudience() != null && jwt.getAudience().stream().anyMatch(accepted::contains)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token",
                        "The token is not meant for this API (audience)", null));
    }

    private static String rootCause(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.toString();
    }
}
