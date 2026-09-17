package ai.mindconnect.adminui.namespaces;

import ai.mindconnect.adminui.branding.BrandingProperties;
import ai.mindconnect.adminui.branding.BrandingVariant;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

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
    void thePageSaysWhatHappenedAndOffersTheWayOut() {
        ResponseEntity<String> answer = new NoAccessController(new BrandingProperties()).noAccess(from("localhost"));

        assertThat(answer.getStatusCode().value()).as("not a page that pretends to be fine").isEqualTo(403);
        assertThat(answer.getBody())
                .contains("<title>No access — Mindconnect Agent Runtime</title>")
                .contains("not in any namespace")
                .contains("Ask an administrator to invite you")
                .contains("href=\"/admin/logout\"");
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

        String page = new NoAccessController(branding).noAccess(from("erni.mindconnect.ai")).getBody();

        assertThat(page).contains("<title>No access — ERNI AI</title>")
                .contains("<h1 id=\"brand-title\">ERNI AI</h1>")
                .contains("src=\"/branding/logo.svg\"")
                .contains("href=\"/branding/erni.css\"");
        assertThat(new NoAccessController(branding).noAccess(from("app.example.com")).getBody())
                .as("an unbranded host keeps the shipped name").contains("Mindconnect Agent Runtime");
    }

    @Test
    void aBrandNameWithMarkupInItCannotBreakOut() {
        BrandingProperties branding = new BrandingProperties();
        branding.setTitle("<script>alert(1)</script>");
        branding.setStylesheets(List.of("/branding/\"onload=x"));

        String page = new NoAccessController(branding).noAccess(from("localhost")).getBody();

        assertThat(page).doesNotContain("<script>alert(1)</script>")
                .contains("&lt;script&gt;alert(1)&lt;/script&gt;")
                .contains("/branding/&quot;onload=x");
    }
}
