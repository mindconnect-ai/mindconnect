package ai.mindconnect.adminui.branding;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules an installation relies on: unset means "as shipped", empty means
 * "none", and the settings that can stand in for each other do.
 */
class BrandingPropertiesTest {

    @Test
    void unset_is_what_the_app_looked_like_before() {
        BrandingProperties branding = new BrandingProperties();

        assertThat(branding.getTitle()).isEqualTo("Mindconnect Agent Runtime");
        assertThat(branding.getLogo()).isEqualTo("/img/logo.svg");
        assertThat(branding.getLogoHref()).isEqualTo("/admin/agents");
        assertThat(branding.getTheme()).isEqualTo("amethyst");
        assertThat(branding.stylesheetUrls()).isEmpty();
        assertThat(branding.getAssetsDir()).isNull();
    }

    @Test
    void the_tab_follows_the_heading_until_it_is_given_its_own() {
        BrandingProperties branding = new BrandingProperties();
        branding.setTitle("ACME Assistants");

        assertThat(branding.getDocumentTitle()).isEqualTo("ACME Assistants");

        branding.setDocumentTitle("ACME Admin");
        assertThat(branding.getDocumentTitle()).isEqualTo("ACME Admin");
    }

    @Test
    void the_tab_icon_follows_the_logo_until_it_is_given_its_own() {
        BrandingProperties branding = new BrandingProperties();
        branding.setLogo("/branding/acme.svg");

        assertThat(branding.getFavicon()).isEqualTo("/branding/acme.svg");

        branding.setFavicon("/branding/acme.ico");
        assertThat(branding.getFavicon()).isEqualTo("/branding/acme.ico");
    }

    @Test
    void an_empty_logo_means_none_rather_than_the_shipped_one() {
        BrandingProperties branding = new BrandingProperties();
        branding.setLogo("");

        assertThat(branding.getLogo()).isNull();
        assertThat(branding.getFavicon()).isNull();
    }

    @Test
    void an_empty_value_falls_back_where_there_has_to_be_one() {
        BrandingProperties branding = new BrandingProperties();
        branding.setTitle("  ");
        branding.setTheme("");
        branding.setLogoHref(null);
        branding.setAssetsDir("  ");

        assertThat(branding.getTitle()).isEqualTo("Mindconnect Agent Runtime");
        assertThat(branding.getTheme()).isEqualTo("amethyst");
        assertThat(branding.getLogoHref()).isEqualTo("/admin/agents");
        assertThat(branding.getAssetsDir()).isNull();
    }

    @Test
    void blank_stylesheet_entries_are_dropped() {
        BrandingProperties branding = new BrandingProperties();
        branding.setStylesheets(Arrays.asList(" /branding/acme.css ", "", "  "));

        assertThat(branding.stylesheetUrls()).isEqualTo(List.of("/branding/acme.css"));
    }
}
