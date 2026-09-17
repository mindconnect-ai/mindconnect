package ai.mindconnect.adminui.namespaces;

import ai.mindconnect.adminui.branding.BrandingNamespace;
import ai.mindconnect.adminui.branding.BrandingProperties;
import ai.mindconnect.adminui.branding.BrandingVariant;
import ai.mindconnect.agent.Email;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.namespace.adapter.memory.InMemoryNamespaceRepository;
import ai.mindconnect.namespace.service.NamespaceService;
import ai.mindconnect.user.adapter.memory.InMemoryUserRepository;
import ai.mindconnect.user.domain.User;
import ai.mindconnect.user.service.UserService;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The filter around {@link NamespaceOnboarding}: once per session, out of the
 * way of what has to work without a namespace, and a browser that has none is
 * sent to the page that says so.
 */
class NamespaceOnboardingFilterTest {

    private static final Instant NOW = Instant.parse("2026-09-17T12:00:00Z");
    private static final String HOST = "erni.mindconnect.ai";

    private final InMemoryNamespaceRepository store = new InMemoryNamespaceRepository();
    private final UserService users = new UserService(new InMemoryUserRepository(), Clock.fixed(NOW, ZoneOffset.UTC));
    private final NamespaceService namespaces = new NamespaceService(store, Namespace.DEFAULT,
            Clock.fixed(NOW, ZoneOffset.UTC), List.of(),
            id -> users.find(id).map(User::email).flatMap(Email::parse),
            "erni.example", List.of(Email.of("chief@erni.example")));
    private final NamespaceOnboardingFilter filter =
            new NamespaceOnboardingFilter(new NamespaceOnboarding(namespaces, users, branding()));

    private static BrandingProperties branding() {
        BrandingProperties properties = new BrandingProperties();
        BrandingVariant erni = new BrandingVariant();
        erni.setUrlPattern(HOST);
        erni.setTitle("ERNI AI");
        BrandingNamespace namespace = new BrandingNamespace();
        namespace.setCreator("david@erni.example");
        erni.setNamespace(namespace);
        LinkedHashMap<String, BrandingVariant> variants = new LinkedHashMap<>();
        variants.put("erni", erni);
        properties.setSwitch(variants);
        return properties;
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void signedIn(String name, String email) {
        UserId id = UserId.of(name);
        users.recordLogin(id, "sub-" + name, "https://auth.example", name, email);
        OidcIdToken token = OidcIdToken.withTokenValue("id").subject("sub-" + name)
                .claim(StandardClaimNames.PREFERRED_USERNAME, name)
                .issuedAt(NOW).expiresAt(NOW.plusSeconds(60)).build();
        DefaultOidcUser principal = new DefaultOidcUser(AuthorityUtils.createAuthorityList("ROLE_USER"), token);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "n/a", principal.getAuthorities()));
    }

    private static MockHttpServletRequest get(String path, MockHttpSession session) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServerName(HOST);
        request.addHeader("Accept", "text/html,application/xhtml+xml");
        if (session != null) request.setSession(session);
        return request;
    }

    @Test
    void theFirstRequestOnboards_andTheSessionRemembersIt() throws Exception {
        signedIn("david", "david@erni.example");
        MockHttpSession session = new MockHttpSession();

        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(get("/", session), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).as("the request goes on").isNotNull();
        assertThat(session.getAttribute(NamespaceOnboardingFilter.DONE)).isEqualTo(Boolean.TRUE);
        assertThat(store.findById(new Namespace("erni"))).isPresent();

        // A second request must not repeat the work: with the namespace removed
        // behind its back, an onboarding that ran again would create it once more.
        store.deleteById(new Namespace("erni"));
        filter.doFilter(get("/admin/api/agents", session), new MockHttpServletResponse(), new MockFilterChain());
        assertThat(store.findById(new Namespace("erni"))).isEmpty();
    }

    @Test
    void aBrowserWithoutANamespaceIsSignedOutAndSentBackToTheProvider() throws Exception {
        signedIn("stranger", "stranger@example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(get("/", new MockHttpSession()), response, chain);

        assertThat(response.getRedirectedUrl()).as("the sign-out is what makes coming back as somebody else real")
                .isEqualTo(NamespaceOnboardingFilter.SIGN_OUT);
        assertThat(response.getCookie(NamespaceOnboardingFilter.RELOGIN_COOKIE)).isNotNull()
                .satisfies(note -> {
                    assertThat(note.getValue()).isEqualTo("1");
                    assertThat(note.getMaxAge()).as("good for one attempt, not forever").isEqualTo(120);
                    assertThat(note.isHttpOnly()).isTrue();
                });
        assertThat(chain.getRequest()).as("and goes no further").isNull();
    }

    @Test
    void withNoProviderToComeBackFromTheyGetThePageInstead() throws Exception {
        NamespaceOnboardingFilter noAuth = new NamespaceOnboardingFilter(
                new NamespaceOnboarding(namespaces, users, branding()), false);
        signedIn("stranger", "stranger@example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();

        noAuth.doFilter(get("/", new MockHttpSession()), response, new MockFilterChain());

        assertThat(response.getRedirectedUrl()).isEqualTo(NamespaceOnboardingFilter.NO_ACCESS);
        assertThat(response.getCookie(NamespaceOnboardingFilter.RELOGIN_COOKIE)).isNull();
    }

    @Test
    void everythingElseIsToldForbiddenAndNothingMore() throws Exception {
        signedIn("stranger", "stranger@example.com");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/api/agents");
        request.setServerName(HOST);
        request.addHeader("Accept", "application/json");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void thePageItselfTheWayOutAndTheAssetsStayOpen() throws Exception {
        signedIn("stranger", "stranger@example.com");

        for (String path : List.of(NamespaceOnboardingFilter.NO_ACCESS, "/admin/logout", "/login",
                "/branding/erni.css", "/css/app.css", "/sui/sui.css", "/favicon.ico")) {
            MockFilterChain chain = new MockFilterChain();
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(get(path, new MockHttpSession()), response, chain);
            assertThat(chain.getRequest()).as(path + " must work without a namespace").isNotNull();
            assertThat(response.getRedirectedUrl()).as(path).isNull();
        }
    }

    @Test
    void aRequestWithoutASignedInUserIsNoneOfItsBusiness() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(get("/", new MockHttpSession()), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(store.findAll()).isEmpty();
    }
}
