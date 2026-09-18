package ai.mindconnect.adminui.namespaces;

import ai.mindconnect.adminui.branding.BrandingProperties;
import ai.mindconnect.adminui.branding.BrandingVariant;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NoAccessControllerTest {

    private static MockHttpServletRequest from(String host) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", NamespaceOnboardingFilter.NO_ACCESS);
        request.setServerName(host);
        return request;
    }

    @Test
    void thePageSaysWhatHappenedAndOffersTheWayBack() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        ResponseEntity<String> answer =
                new NoAccessController(new BrandingProperties()).noAccess(from("localhost"), response);

        assertThat(answer.getStatusCode().value()).as("not a page that pretends to be fine").isEqualTo(403);
        assertThat(answer.getBody())
                .contains("<title>No access — Mindconnect Agent Runtime</title>")
                .contains("Sorry &mdash; you are not registered here.")
                .contains("in no namespace")
                .contains("Ask an administrator to invite you")
                .contains("Sign in with another account")
                .contains("href=\"/admin/logout\"");
    }

    @Test
    void itLeavesTheNoteThatSendsTheNextRequestStraightToTheProvider() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new NoAccessController(new BrandingProperties()).noAccess(from("localhost"), response);

        assertThat(response.getCookie(NamespaceOnboardingFilter.RELOGIN_COOKIE)).isNotNull()
                .satisfies(note -> {
                    assertThat(note.getValue()).isEqualTo("1");
                    assertThat(note.getMaxAge()).as("long enough to read the page, not forever").isEqualTo(600);
                    assertThat(note.isHttpOnly()).isTrue();
                });
    }

    @Test
    void itWearsTheBrandOfTheHostItWasAskedUnder() {
        BrandingProperties branding = new BrandingProperties();
        BrandingVariant erni = new BrandingVariant();
        erni.setUrlPattern("erni.mindconnect.ai");
        erni.setTitle("ERNI AI");
        erni.setLogo("logo.svg");
        erni.setStylesheet("erni.css");
        LinkedHashMap<String, BrandingVariant> variants = new LinkedHashMap<>();
        variants.put("erni", erni);
        branding.setSwitch(variants);

        String page = new NoAccessController(branding)
                .noAccess(from("erni.mindconnect.ai"), new MockHttpServletResponse()).getBody();

        assertThat(page).contains("<title>No access — ERNI AI</title>")
                .contains("<h1 id=\"brand-title\">ERNI AI</h1>")
                .contains("src=\"/branding/logo.svg\"")
                .contains("href=\"/branding/erni.css\"");
        assertThat(new NoAccessController(branding)
                .noAccess(from("app.example.com"), new MockHttpServletResponse()).getBody())
                .as("an unbranded host keeps the shipped name").contains("Mindconnect Agent Runtime");
    }

    @Test
    void aBrandNameWithMarkupInItCannotBreakOut() {
        BrandingProperties branding = new BrandingProperties();
        branding.setTitle("<script>alert(1)</script>");
        branding.setStylesheets(List.of("/branding/\"onload=x"));

        String page = new NoAccessController(branding)
                .noAccess(from("localhost"), new MockHttpServletResponse()).getBody();

        assertThat(page).doesNotContain("<script>alert(1)</script>")
                .contains("&lt;script&gt;alert(1)&lt;/script&gt;")
                .contains("/branding/&quot;onload=x");
    }
}
