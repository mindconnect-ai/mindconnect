package ai.mindconnect.adminui.namespaces;

import ai.mindconnect.adminui.branding.BrandingProperties;
import ai.mindconnect.adminui.branding.BrandingShellController;
import ai.mindconnect.adminui.branding.BrandingVariant;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The page somebody sees who is in no namespace here. It is the login page's
 * sibling — same shell, same branding — so the checks are the same two: it
 * says what happened, and it wears the host's name.
 */
class NoAccessControllerTest {

    private static MockHttpServletRequest from(String host) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", NamespaceOnboardingFilter.NO_ACCESS);
        request.setServerName(host);
        return request;
    }

    private static NoAccessController controller(BrandingProperties branding) {
        return new NoAccessController(branding, new BrandingShellController(branding));
    }

    private static BrandingProperties erni() {
        BrandingProperties branding = new BrandingProperties();
        BrandingVariant erni = new BrandingVariant();
        erni.setUrlPattern("erni.mindconnect.ai");
        erni.setTitle("ERNI AI");
        erni.setLogo("logo.svg");
        erni.setStylesheet("erni.css");
        LinkedHashMap<String, BrandingVariant> variants = new LinkedHashMap<>();
        variants.put("erni", erni);
        branding.setSwitch(variants);
        return branding;
    }

    @Test
    void thePageSaysWhatHappenedAndOffersTheWayBack() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        ResponseEntity<String> answer = controller(new BrandingProperties()).noAccess(from("localhost"), response);

        assertThat(answer.getStatusCode().value()).as("not a page that pretends to be fine").isEqualTo(403);
        assertThat(answer.getBody())
                .contains("<title>No access \u2014 Mindconnect Agent Runtime</title>")
                .contains("Sorry \u2014 you are not registered here.")
                .contains("in no namespace")
                .contains("Ask an administrator to invite you")
                .contains("Sign in with another account")
                .contains("href=\"/admin/logout\"");
    }

    @Test
    void itLeavesTheNoteThatSendsTheNextRequestStraightToTheProvider() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller(new BrandingProperties()).noAccess(from("localhost"), response);

        assertThat(response.getCookie(NamespaceOnboardingFilter.RELOGIN_COOKIE)).isNotNull()
                .satisfies(note -> {
                    assertThat(note.getValue()).isEqualTo("1");
                    assertThat(note.getMaxAge()).as("long enough to read the page, not forever").isEqualTo(600);
                    assertThat(note.isHttpOnly()).isTrue();
                });
    }

    @Test
    void itWearsTheBrandOfTheHostItWasAskedUnder() {
        BrandingProperties branding = erni();

        String page = controller(branding).noAccess(from("erni.mindconnect.ai"), new MockHttpServletResponse())
                .getBody();

        assertThat(page).contains("<title>No access \u2014 ERNI AI</title>")
                .contains("<h1 id=\"brand-title\">ERNI AI</h1>")
                .contains("href=\"/branding/erni.css\"");
        assertThat(controller(branding).noAccess(from("app.example.com"), new MockHttpServletResponse()).getBody())
                .as("an unbranded host keeps the shipped name").contains("Mindconnect Agent Runtime");
    }

    @Test
    void aBrandNameWithMarkupInItCannotBreakOut() {
        BrandingProperties branding = new BrandingProperties();
        branding.setTitle("<script>alert(1)</script>");

        String page = controller(branding).noAccess(from("localhost"), new MockHttpServletResponse()).getBody();

        assertThat(page).doesNotContain("<script>alert(1)</script>")
                .contains("&lt;script&gt;alert(1)&lt;/script&gt;");
    }
}
