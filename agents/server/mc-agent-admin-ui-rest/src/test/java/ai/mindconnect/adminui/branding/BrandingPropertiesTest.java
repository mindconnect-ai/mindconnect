package ai.mindconnect.adminui.branding;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules an installation relies on: unset means "as shipped", empty means
 * "none", the settings that can stand in for each other do — and, once a
 * {@code switch} is configured, the host decides which of them apply.
 */
class BrandingPropertiesTest {

    @Test
    void unset_is_what_the_app_looked_like_before() {
        Branding branding = new BrandingProperties().resolve();

        assertThat(branding.title()).isEqualTo("Mindconnect Agent Runtime");
        assertThat(branding.logo()).isEqualTo("/img/logo.svg");
        assertThat(branding.logoHref()).isEqualTo("/admin/agents");
        assertThat(branding.theme()).isEqualTo("amethyst");
        assertThat(branding.stylesheets()).isEmpty();
        assertThat(branding.pickerDisabled()).isFalse();
        assertThat(branding.pickerThemes()).isEmpty();
    }

    @Test
    void the_tab_follows_the_heading_until_it_is_given_its_own() {
        BrandingProperties properties = new BrandingProperties();
        properties.setTitle("ACME Assistants");

        assertThat(properties.resolve().documentTitle()).isEqualTo("ACME Assistants");

        properties.setDocumentTitle("ACME Admin");
        assertThat(properties.resolve().documentTitle()).isEqualTo("ACME Admin");
        assertThat(properties.resolve().title()).isEqualTo("ACME Assistants");
    }

    @Test
    void the_tab_icon_follows_the_logo_until_it_is_given_its_own() {
        BrandingProperties properties = new BrandingProperties();
        properties.setLogo("/branding/acme.svg");

        assertThat(properties.resolve().favicon()).isEqualTo("/branding/acme.svg");

        properties.setFavicon("/branding/acme.ico");
        assertThat(properties.resolve().favicon()).isEqualTo("/branding/acme.ico");
    }

    @Test
    void an_empty_logo_means_none_rather_than_the_shipped_one() {
        BrandingProperties properties = new BrandingProperties();
        properties.setLogo("");

        assertThat(properties.resolve().logo()).isNull();
        assertThat(properties.resolve().favicon()).isNull();
    }

    @Test
    void a_bare_file_name_is_a_file_in_the_assets_directory() {
        BrandingProperties properties = new BrandingProperties();
        properties.setLogo("acme.svg");
        properties.setStylesheet("acme.css");

        Branding branding = properties.resolve();

        assertThat(branding.logo()).isEqualTo("/branding/acme.svg");
        assertThat(branding.stylesheets()).containsExactly("/branding/acme.css");
    }

    @Test
    void a_path_or_an_absolute_url_is_left_alone() {
        BrandingProperties properties = new BrandingProperties();
        properties.setLogo("/img/logo.svg");
        properties.setStylesheets(List.of("https://cdn.example.com/acme.css"));

        Branding branding = properties.resolve();

        assertThat(branding.logo()).isEqualTo("/img/logo.svg");
        assertThat(branding.stylesheets()).containsExactly("https://cdn.example.com/acme.css");
    }

    @Test
    void blank_values_fall_back_where_there_has_to_be_one() {
        BrandingProperties properties = new BrandingProperties();
        properties.setTitle("  ");
        properties.setTheme("");
        properties.setLogoHref(null);
        properties.setAssetsDir("  ");

        Branding branding = properties.resolve();

        assertThat(branding.title()).isEqualTo("Mindconnect Agent Runtime");
        assertThat(branding.theme()).isEqualTo("amethyst");
        assertThat(branding.logoHref()).isEqualTo("/admin/agents");
        assertThat(properties.getAssetsDir()).isNull();
    }

    @Test
    void blank_stylesheet_entries_are_dropped() {
        BrandingProperties properties = new BrandingProperties();
        properties.setStylesheets(Arrays.asList(" /branding/acme.css ", "", "  "));

        assertThat(properties.resolve().stylesheets()).containsExactly("/branding/acme.css");
    }

    // ── switch: one process, several brands ──────────────────────────────

    private static BrandingProperties twoBrands() {
        BrandingProperties properties = new BrandingProperties();
        properties.setTitle("Mindconnect Agent Runtime");

        BrandingVariant acme = new BrandingVariant();
        acme.setUrlPattern("*.acme.*");
        acme.setTitle("ACME AI");
        acme.setLogo("acme.svg");
        acme.setStylesheet("acme-sui.css");
        StylePicker fixed = new StylePicker();
        fixed.setDisabled(true);
        acme.setStylePicker(fixed);

        BrandingVariant own = new BrandingVariant();
        own.setUrlPattern("app.mindconnect.ai");
        own.setLogo("mindconnect-logo.svg");
        StylePicker two = new StylePicker();
        two.setThemes(List.of("amethyst", "default"));
        own.setStylePicker(two);

        Map<String, BrandingVariant> variants = new LinkedHashMap<>();
        variants.put("acme", acme);
        variants.put("mindconnect", own);
        properties.setSwitch(variants);
        return properties;
    }

    @Test
    void the_host_picks_the_brand() {
        BrandingProperties properties = twoBrands();

        Branding acme = properties.resolve("agents.acme.com");

        assertThat(acme.title()).isEqualTo("ACME AI");
        assertThat(acme.logo()).isEqualTo("/branding/acme.svg");
        assertThat(acme.stylesheets()).containsExactly("/branding/acme-sui.css");
        assertThat(acme.pickerDisabled()).isTrue();
        assertThat(acme.pickerAttribute()).isEqualTo("off");
    }

    @Test
    void a_pattern_matches_whole_labels_and_ignores_case_and_port() {
        BrandingProperties properties = twoBrands();

        assertThat(properties.resolve("AI.ACME.CO.UK").title()).isEqualTo("ACME AI");
        assertThat(properties.resolve("agents.acme.com:9090").title()).isEqualTo("ACME AI");
        assertThat(properties.resolve("app.mindconnect.ai").logo())
                .isEqualTo("/branding/mindconnect-logo.svg");
    }

    @Test
    void what_a_variant_leaves_unset_comes_from_the_top_level() {
        BrandingProperties properties = twoBrands();
        properties.setTheme("clody");
        properties.setLogoHref("/chat");

        Branding acme = properties.resolve("agents.acme.com");

        assertThat(acme.theme()).isEqualTo("clody");
        assertThat(acme.logoHref()).isEqualTo("/chat");
        // The tab follows the variant's own title, not the installation's.
        assertThat(acme.documentTitle()).isEqualTo("ACME AI");
    }

    @Test
    void a_host_that_matches_nothing_gets_the_top_level_branding() {
        BrandingProperties properties = twoBrands();

        Branding fallback = properties.resolve("localhost");

        assertThat(fallback.title()).isEqualTo("Mindconnect Agent Runtime");
        assertThat(fallback.logo()).isEqualTo("/img/logo.svg");
        assertThat(fallback.pickerDisabled()).isFalse();
        assertThat(properties.resolve(null).title()).isEqualTo("Mindconnect Agent Runtime");
    }

    @Test
    void the_first_matching_entry_wins() {
        BrandingProperties properties = new BrandingProperties();
        BrandingVariant specific = new BrandingVariant();
        specific.setUrlPattern("ai.acme.com");
        specific.setTitle("The specific one");
        BrandingVariant catchAll = new BrandingVariant();
        catchAll.setUrlPattern("*");
        catchAll.setTitle("The catch-all");
        Map<String, BrandingVariant> variants = new LinkedHashMap<>();
        variants.put("specific", specific);
        variants.put("everything", catchAll);
        properties.setSwitch(variants);

        assertThat(properties.resolve("ai.acme.com").title()).isEqualTo("The specific one");
        assertThat(properties.resolve("somewhere.else").title()).isEqualTo("The catch-all");
    }

    @Test
    void an_entry_without_a_pattern_is_never_chosen() {
        BrandingProperties properties = new BrandingProperties();
        BrandingVariant forgotten = new BrandingVariant();
        forgotten.setTitle("Nobody gets this");
        properties.setSwitch(Map.of("forgotten", forgotten));

        assertThat(properties.resolve("anything.at.all").title())
                .isEqualTo("Mindconnect Agent Runtime");
    }

    @Test
    void the_picker_can_be_narrowed_instead_of_turned_off() {
        Branding mindconnect = twoBrands().resolve("app.mindconnect.ai");

        assertThat(mindconnect.pickerDisabled()).isFalse();
        assertThat(mindconnect.pickerThemes()).containsExactly("amethyst", "default");
        assertThat(mindconnect.pickerAttribute()).isEqualTo("amethyst default");
    }

    @Test
    void the_top_level_can_disable_the_picker_for_every_host() {
        BrandingProperties properties = twoBrands();
        StylePicker off = new StylePicker();
        off.setDisabled(true);
        properties.setStylePicker(off);

        assertThat(properties.resolve("localhost").pickerDisabled()).isTrue();
        // ...and a variant that says otherwise still decides for its own host.
        BrandingVariant own = properties.getSwitch().get("mindconnect");
        own.getStylePicker().setDisabled(false);
        assertThat(properties.resolve("app.mindconnect.ai").pickerDisabled()).isFalse();
    }

    @Test
    void a_brand_may_name_the_namespace_its_hosts_work_in() {
        BrandingProperties properties = new BrandingProperties();
        BrandingVariant acme = new BrandingVariant();
        acme.setUrlPattern("acme.example.com");
        acme.setTitle("ACME AI");
        BrandingNamespace namespace = new BrandingNamespace();
        namespace.setAdmins(java.util.List.of("chief@acme.example", "david@acme.example"));
        acme.setNamespace(namespace);
        properties.setSwitch(new java.util.LinkedHashMap<>(java.util.Map.of("acme", acme)));

        Branding branded = properties.resolve("acme.example.com");

        assertThat(branded.namespace()).as("the entry's own name is the id").isEqualTo("acme");
        assertThat(branded.namespaceAdmins()).containsExactly(
                ai.mindconnect.agent.Email.of("chief@acme.example"),
                ai.mindconnect.agent.Email.of("david@acme.example"));
        assertThat(branded.hasNamespace()).isTrue();
    }

    @Test
    void creator_is_the_short_form_of_one_admin_and_comes_first() {
        BrandingNamespace namespace = new BrandingNamespace();
        namespace.setCreator("David@Acme.example");
        namespace.setAdmins(java.util.List.of("chief@acme.example", " ", "david@acme.example"));

        assertThat(namespace.addresses()).containsExactly(
                ai.mindconnect.agent.Email.of("david@acme.example"),
                ai.mindconnect.agent.Email.of("chief@acme.example"));
    }

    @Test
    void a_brand_without_a_namespace_block_has_none_and_inherits_none() {
        BrandingProperties properties = new BrandingProperties();
        BrandingVariant plain = new BrandingVariant();
        plain.setUrlPattern("plain.example.com");
        BrandingVariant acme = new BrandingVariant();
        acme.setUrlPattern("acme.example.com");
        BrandingNamespace namespace = new BrandingNamespace();
        namespace.setId("acme-ai");
        namespace.setCreator("david@acme.example");
        acme.setNamespace(namespace);
        java.util.LinkedHashMap<String, BrandingVariant> variants = new java.util.LinkedHashMap<>();
        variants.put("acme", acme);
        variants.put("plain", plain);
        properties.setSwitch(variants);

        assertThat(properties.resolve("acme.example.com").namespace())
                .as("an explicit id wins over the entry's name").isEqualTo("acme-ai");
        assertThat(properties.resolve("plain.example.com").namespace()).isNull();
        assertThat(properties.resolve("plain.example.com").hasNamespace()).isFalse();
        assertThat(properties.resolve("nothing.example.com").namespace()).isNull();
        assertThat(properties.resolve(null).namespaceAdmins()).isEmpty();
    }
}
