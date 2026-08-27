package ai.mindconnect.adminui;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.DelegatingAuthenticationEntryPoint;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * BFF (Backend-for-Frontend) security configuration.
 *
 * <p>The OAuth/OIDC flow runs entirely in Spring Security: the browser is
 * redirected to Keycloak, gets bounced back to the
 * {@code /login/oauth2/code/keycloak} callback that Spring registers
 * automatically, and from then on rides a server-side {@code HttpSession}
 * with an {@code HttpOnly} JSESSIONID cookie. Tokens (access + refresh) never
 * reach the browser, which removes the entire XSS-exfiltration attack class
 * the previous {@code localStorage}-based resource-server setup was exposed
 * to.
 *
 * <p>Two filter chains:
 * <ul>
 *   <li><b>auth enabled</b> ({@code mindconnect.auth.enabled=true}): static
 *       assets are public; everything else requires a logged-in session.
 *       Browser navigations to a protected page are redirected to Keycloak,
 *       but {@code fetch} / XHR calls get a clean {@code 401} so the SPA
 *       can decide for itself when to bounce the user through the login
 *       flow. CSRF is on, with the token shipped via a JavaScript-readable
 *       {@code XSRF-TOKEN} cookie so the SPA can echo it back in the
 *       {@code X-XSRF-TOKEN} header on mutating requests.</li>
 *   <li><b>auth disabled</b> (default in local dev without Keycloak): all
 *       permitAll, CSRF off, no OIDC wiring.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    @ConditionalOnProperty(name = "mindconnect.auth.enabled", havingValue = "true")
    SecurityFilterChain securedFilterChain(
            HttpSecurity http,
            ClientRegistrationRepository clientRegistrations) throws Exception {

        // Spring's default CSRF handler hides the raw token behind a
        // BREACH-mitigation wrapper that JavaScript can't consume. The
        // attribute-handler below restores the plain-token behaviour
        // expected by SPAs that read the cookie and echo it as a header.
        var csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null);

        // Only remember browser navigations (Accept: text/html) as the
        // post-login return target. Without this, an XHR call that 401s —
        // e.g. /admin/api/agents from the SPA — gets remembered, and after
        // the OAuth round-trip Spring sends the browser there as a fresh
        // GET. The user sees raw JSON instead of the SPA.
        var htmlOnlyRequestCache = new HttpSessionRequestCache();
        htmlOnlyRequestCache.setRequestMatcher(htmlAcceptMatcher());

        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/index.html", "/favicon.ico",
                                 "/js/**", "/css/**",
                                 "/sui/**", "/sui-ext/**",
                                 // Explicit login landing page (served as
                                 // a static HTML resource forwarded to by
                                 // SpaController) — must be reachable
                                 // unauthenticated so users can start (or
                                 // restart after a failure) the OAuth flow.
                                 "/login", "/login.html",
                                 "/error").permitAll()
                .anyRequest().authenticated()
            )
            .requestCache(rc -> rc.requestCache(htmlOnlyRequestCache))
            .oauth2Login(oauth -> oauth
                // After login, restore the originally requested URL via
                // the saved-request mechanism (HTML-only) — falls back
                // to "/" when no saved request exists. The {@code false}
                // means: use the saved request if there is one, otherwise
                // the default.
                .defaultSuccessUrl("/", false)
                // On OAuth failure (expired ID-token, stale authorization
                // request, …) bounce back to the explicit /login page
                // with an error message. The user clicks to retry — no
                // automatic redirect, no loops.
                .failureUrl("/login?error=oidc")
            )
            .logout(logout -> logout
                // The header renders logout as a plain link (UiLink), which is a
                // GET — so accept GET /admin/logout. Logout only ends the session
                // and bounces to the IdP, so allowing GET here is acceptable and
                // keeps logout inside the server-rendered UiNode model (no JS).
                .logoutRequestMatcher(new AntPathRequestMatcher("/admin/logout", "GET"))
                .logoutSuccessHandler(oidcLogoutSuccessHandler(clientRegistrations))
            )
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(csrfHandler)
            )
            // Exception handling: for browser navigations (Accept: text/html)
            // we redirect to the Keycloak login URL, for everything else we
            // answer with 401 so the SPA can do its own redirect handling
            // without having to follow a 302.
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(htmlOrApiEntryPoint())
            );

        // Same-origin framing only: the admin shell embeds its own pages
        // (Swagger UI in the API section); foreign sites still can't frame us.
        http.headers(headers -> headers.frameOptions(f -> f.sameOrigin()));
        return http.build();
    }

    @Bean
    @ConditionalOnProperty(name = "mindconnect.auth.enabled", havingValue = "false", matchIfMissing = true)
    SecurityFilterChain openFilterChain(
            HttpSecurity http,
            @Value("${mindconnect.auth.dev-user:mc_user}") String devUser) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            // No Keycloak in this mode, but the rest of the app still expects an
            // authenticated OidcUser principal (controllers read userId from it).
            // Inject a fixed dev user so behaviour matches the secured chain.
            .addFilterBefore(devUserFilter(devUser), AuthorizationFilter.class);
        // Same-origin framing only: the admin shell embeds its own pages
        // (Swagger UI in the API section); foreign sites still can't frame us.
        http.headers(headers -> headers.frameOptions(f -> f.sameOrigin()));
        return http.build();
    }

    /**
     * Filter used only by {@link #openFilterChain} (auth disabled): places a
     * fixed {@link OidcUser} ({@code preferred_username = devUser}) into the
     * SecurityContext for every request, so {@code @AuthenticationPrincipal
     * OidcUser} works identically to the Keycloak-backed secured chain and the
     * app has a stable userId without standing up Keycloak.
     */
    private OncePerRequestFilter devUserFilter(String devUser) {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request,
                                            HttpServletResponse response,
                                            FilterChain chain)
                    throws ServletException, IOException {
                // Spring's AnonymousAuthenticationFilter has already populated
                // the context with an anonymous token by the time this runs, so
                // we replace it whenever the principal isn't our OidcUser yet.
                var current = SecurityContextHolder.getContext().getAuthentication();
                if (current == null || !(current.getPrincipal() instanceof OidcUser)) {
                    OidcUser user = devOidcUser(devUser);
                    var auth = new OAuth2AuthenticationToken(
                            user, user.getAuthorities(), "dev");
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
                chain.doFilter(request, response);
            }
        };
    }

    /** Builds the synthetic OidcUser used when auth is disabled. */
    private OidcUser devOidcUser(String devUser) {
        OidcIdToken idToken = OidcIdToken.withTokenValue("dev")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(Duration.ofDays(3650)))
                .subject(devUser)
                .claim(StandardClaimNames.PREFERRED_USERNAME, devUser)
                .claim(StandardClaimNames.NAME, devUser)
                .build();
        return new DefaultOidcUser(
                List.of(new SimpleGrantedAuthority("ROLE_USER")), idToken);
    }

    /**
     * Customises the OIDC ID-token decoder with a 5-minute clock-skew
     * tolerance. Without this, even a small drift between the Spring
     * host and the Keycloak container (laptop sleep/wake, NTP hiccup,
     * a paused Docker VM, …) makes the {@code iat}/{@code exp} timestamp
     * check fail with {@code [invalid_id_token] Jwt expired at …},
     * which Spring's default flow converts into an
     * {@code OAuth2AuthenticationException} → failure redirect → fresh
     * login flow loop. Five minutes is generous for dev, still strict
     * enough that a properly-expired token is rejected.
     *
     * <p>Bound to the same {@code mindconnect.auth.enabled=true} switch
     * as the secured filter chain so the bean only exists when OIDC is
     * actually wired up.
     */
    @Bean
    @ConditionalOnProperty(name = "mindconnect.auth.enabled", havingValue = "true")
    OidcIdTokenDecoderFactory idTokenDecoderFactory() {
        var factory = new OidcIdTokenDecoderFactory();
        factory.setJwtValidatorFactory((ClientRegistration registration) ->
                new JwtTimestampValidator(Duration.ofMinutes(5)));
        return factory;
    }

    /**
     * Picks the right unauthorised-response behaviour by the request's
     * {@code Accept} header: HTML → redirect to the explicit
     * {@code /login} landing page (which has a button that starts the
     * OAuth flow on user click); everything else (typically JSON
     * {@code fetch} / XHR) → bare 401 so the SPA can decide how to
     * bounce the user.
     *
     * <p>The {@code /login} indirection breaks the automatic
     * redirect-loop the previous direct-to-Keycloak entry point was
     * prone to: a failed OAuth callback would 302 back to a protected
     * URL, which would 302 to Keycloak, which would 302 back, … The
     * landing page forces the loop to terminate at the first hop and
     * requires a deliberate user click to retry.
     */
    private DelegatingAuthenticationEntryPoint htmlOrApiEntryPoint() {
        var entryPoints = new LinkedHashMap<RequestMatcher, org.springframework.security.web.AuthenticationEntryPoint>();
        entryPoints.put(htmlAcceptMatcher(), new LoginUrlAuthenticationEntryPoint("/login"));

        var entryPoint = new DelegatingAuthenticationEntryPoint(entryPoints);
        entryPoint.setDefaultEntryPoint(new HttpStatusEntryPoint(org.springframework.http.HttpStatus.UNAUTHORIZED));
        return entryPoint;
    }

    /**
     * Matches requests whose {@code Accept} header is exactly
     * {@code text/html}. Used both for the login entry-point routing
     * (HTML → redirect; everything else → 401) and the request cache
     * (only remember HTML navigations as the post-login return target).
     */
    private MediaTypeRequestMatcher htmlAcceptMatcher() {
        var m = new MediaTypeRequestMatcher(MediaType.TEXT_HTML);
        m.setUseEquals(true);
        return m;
    }

    /**
     * Builds a logout success handler that calls Keycloak's RP-initiated
     * end-session endpoint so the user is signed out at the identity
     * provider too, not just from the local session.
     */
    private OidcClientInitiatedLogoutSuccessHandler oidcLogoutSuccessHandler(
            ClientRegistrationRepository repo) {
        var handler = new OidcClientInitiatedLogoutSuccessHandler(repo);
        handler.setPostLogoutRedirectUri("{baseUrl}/");
        return handler;
    }
}
