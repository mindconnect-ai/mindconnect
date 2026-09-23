package ai.mindconnect.adminui.namespaces;

import ai.mindconnect.agent.Email;
import ai.mindconnect.agent.Namespace;
import ai.mindconnect.agent.Scope;
import ai.mindconnect.agent.ScopeSupplier;
import ai.mindconnect.agent.UserId;
import ai.mindconnect.namespace.adapter.memory.InMemoryNamespaceRepository;
import ai.mindconnect.namespace.domain.NamespaceRole;
import ai.mindconnect.namespace.service.NamespaceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The second line under the roles. The first two tests are the contract; the
 * long lists below are the point of a list of what is open — every route a
 * user may reach is written down, and adding one without noticing shows up
 * here.
 */
class NamespaceAccessInterceptorTest {

    private static final Instant NOW = Instant.parse("2026-09-17T12:00:00Z");
    private static final Namespace ACME = new Namespace("acme");
    private static final UserId DAVID = UserId.of("david");
    private static final UserId ALICE = UserId.of("alice");

    private final InMemoryNamespaceRepository store = new InMemoryNamespaceRepository();
    private final NamespaceService namespaces = new NamespaceService(store, Namespace.DEFAULT,
            Clock.fixed(NOW, ZoneOffset.UTC), List.of(),
            id -> Optional.of(Email.of(id.value() + "@acme.example")), "acme.example", List.of());
    private final NamespaceAccessInterceptor interceptor =
            new NamespaceAccessInterceptor(namespaces, ScopeSupplier.fixed(Scope.of(ACME, DAVID)));

    NamespaceAccessInterceptorTest() {
        namespaces.create("acme", "ACME", DAVID);
        namespaces.invite(ACME, DAVID, Email.of("alice@acme.example"), NamespaceRole.USER);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void signedIn(UserId user) {
        OidcIdToken token = OidcIdToken.withTokenValue("id").subject("sub-" + user.value())
                .claim(StandardClaimNames.PREFERRED_USERNAME, user.value())
                .issuedAt(NOW).expiresAt(NOW.plusSeconds(60)).build();
        DefaultOidcUser principal = new DefaultOidcUser(AuthorityUtils.createAuthorityList("ROLE_USER"), token);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "n/a", principal.getAuthorities()));
    }

    private MockHttpServletResponse call(String path) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        interceptor.preHandle(new MockHttpServletRequest("GET", path), response, new Object());
        return response;
    }

    private boolean allowed(String path) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        return interceptor.preHandle(new MockHttpServletRequest("GET", path), response, new Object());
    }

    @Test
    void anAdminOfTheNamespaceReachesEverything() throws Exception {
        signedIn(DAVID);

        for (String path : List.of("/admin/agents", "/admin/api/agents", "/workflow-admin",
                "/admin/llm-configs", "/admin/vector-stores", "/mcp-gateway", "/admin/api-explorer")) {
            assertThat(allowed(path)).as(path).isTrue();
        }
    }

    @Test
    void aUserOfTheNamespaceIsTurnedAwayFromWhatShapesIt() throws Exception {
        signedIn(ALICE);

        for (String path : List.of("/admin/agents", "/admin/api/agents", "/admin/api/agents/new",
                "/workflow-admin", "/workflow-admin/wf-1/run", "/admin/llm-configs",
                "/admin/skills", "/admin/tools", "/admin/vector-stores", "/mcp-gateway",
                "/admin/api-explorer", "/registry", "/admin/migrations", "/admin/extensions")) {
            MockHttpServletResponse response = call(path);
            assertThat(response.getStatus()).as(path).isEqualTo(403);
            assertThat(response.getErrorMessage()).as(path).contains("Only an admin of 'acme'");
        }
    }

    @Test
    void whatAUserIsHereForStaysOpen() throws Exception {
        signedIn(ALICE);

        for (String path : List.of("/", "/index.html", "/chat", "/chat/abc",
                "/admin/api/chat/sessions", "/admin/api/sessions", "/admin/api/messages/42",
                "/admin/profile", "/admin/api/profile/environment", "/admin/namespaces",
                "/admin/api/namespaces/switch/acme", "/admin/api/about", "/admin/api/user-stream",
                "/admin/logout", "/no-access", "/css/app.css", "/js/app.js", "/sui/sui.css",
                "/branding/erni.css", "/favicon.ico")) {
            assertThat(allowed(path)).as(path).isTrue();
        }
    }

    @Test
    void theRestApiIsNotOpenEither_aTokenCarriesItsOwnersRights() throws Exception {
        signedIn(ALICE);

        assertThat(call("/api/agents").getStatus()).isEqualTo(403);
        assertThat(call("/v1/responses").getStatus()).isEqualTo(403);
    }

    @Test
    void aPathNobodyThoughtAboutIsAnAdminsAndNotAUsers() throws Exception {
        signedIn(ALICE);

        assertThat(call("/some-screen-added-next-year").getStatus())
                .as("closed by default is the mistake that costs an afternoon, not a namespace")
                .isEqualTo(403);
    }

    @Test
    void aRequestWithoutASignedInUserIsNotItsBusiness() throws Exception {
        assertThat(allowed("/admin/agents")).isTrue();
    }

    @Test
    void aPrefixOnlyMatchesWholeSegments() {
        assertThat(NamespaceAccessInterceptor.isOpen("/chat")).isTrue();
        assertThat(NamespaceAccessInterceptor.isOpen("/chat/session-1")).isTrue();
        assertThat(NamespaceAccessInterceptor.isOpen("/chatter"))
                .as("a screen that merely starts with an open name is not open").isFalse();
        assertThat(NamespaceAccessInterceptor.isOpen("/admin/api/namespaces-secret")).isFalse();
    }
}
