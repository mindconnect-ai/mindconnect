package ai.mindconnect.adminui.branding;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Branding is a pass over the shipped HTML shells, anchored on markers those
 * files carry. Both halves are tested here: that the pass does what it says,
 * and that the real shells still carry the anchors — a removed
 * {@code data-default-theme} would not fail to compile, it would just quietly
 * stop honouring the setting.
 */
class BrandingHtmlTest {

    private static final String SHELL = """
            <!DOCTYPE html>
            <html lang="en" data-default-theme="amethyst">
            <head>
                <title>Mindconnect Agent Runtime</title>
                <link rel="stylesheet" href="/css/app.css">
            </head>
            <body><h1 id="brand-title">Mindconnect Agent Runtime</h1></body>
            </html>
            """;

    private static Branding branding() {
        BrandingProperties properties = new BrandingProperties();
        properties.setTitle("ACME Assistants");
        properties.setFavicon("/branding/acme.png");
        properties.setTheme("clody");
        properties.setStylesheets(List.of("/branding/acme.css", "https://cdn.example.com/acme.css"));
        return properties.resolve();
    }

    @Test
    void the_configured_name_reaches_the_tab_and_the_heading() {
        String out = BrandingHtml.apply(SHELL, branding());

        assertThat(out).contains("<title>ACME Assistants</title>");
        assertThat(out).contains("<h1 id=\"brand-title\">ACME Assistants</h1>");
        assertThat(out).doesNotContain("Mindconnect Agent Runtime");
    }

    @Test
    void the_tab_title_can_differ_from_the_heading() {
        BrandingProperties properties = new BrandingProperties();
        properties.setTitle("ACME Assistants");
        properties.setDocumentTitle("ACME Admin");

        String out = BrandingHtml.apply(SHELL, properties.resolve());

        assertThat(out).contains("<title>ACME Admin</title>");
        assertThat(out).contains("<h1 id=\"brand-title\">ACME Assistants</h1>");
    }

    @Test
    void a_spelled_out_document_title_wins_over_both() {
        String out = BrandingHtml.apply(SHELL, branding(), "Sign in — ACME Assistants");

        assertThat(out).contains("<title>Sign in — ACME Assistants</title>");
    }

    @Test
    void icon_and_stylesheets_go_in_last_so_they_win() {
        String out = BrandingHtml.apply(SHELL, branding());

        assertThat(out).contains("<link rel=\"icon\" href=\"/branding/acme.png\">");
        assertThat(out).contains("<link rel=\"stylesheet\" href=\"/branding/acme.css\">");
        assertThat(out).contains("<link rel=\"stylesheet\" href=\"https://cdn.example.com/acme.css\">");
        assertThat(out.indexOf("/css/app.css")).isLessThan(out.indexOf("/branding/acme.css"));
        assertThat(out.indexOf("/branding/acme.css")).isLessThan(out.indexOf("</head>"));
    }

    @Test
    void the_start_theme_is_written_into_the_shell() {
        assertThat(BrandingHtml.apply(SHELL, branding())).contains("data-default-theme=\"clody\"");
    }

    @Test
    void nothing_configured_leaves_the_shell_as_it_ships() {
        String out = BrandingHtml.apply(SHELL, new BrandingProperties().resolve());

        assertThat(out).contains("<title>Mindconnect Agent Runtime</title>");
        assertThat(out).contains("data-default-theme=\"amethyst\"");
        // The shipped logo doubles as the tab icon; nothing else is added.
        assertThat(out).contains("<link rel=\"icon\" href=\"/img/logo.svg\">");
        assertThat(out).doesNotContain("rel=\"stylesheet\" href=\"/branding");
    }

    @Test
    void a_name_with_markup_in_it_cannot_break_out() {
        BrandingProperties properties = new BrandingProperties();
        properties.setTitle("<script>alert(1)</script>");
        properties.setFavicon("/x.png\" onload=\"alert(1)");

        String out = BrandingHtml.apply(SHELL, properties.resolve());

        assertThat(out).doesNotContain("<script>alert(1)</script>");
        assertThat(out).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
        assertThat(out).contains("href=\"/x.png&quot; onload=&quot;alert(1)\"");
    }

    @Test
    void a_shell_without_the_anchors_survives_untouched() {
        String plain = "<html><head></head><body>hi</body></html>";

        String out = BrandingHtml.apply(plain, branding());

        // No title, no theme attribute, no heading to replace — but the links
        // still go in, since </head> is there.
        assertThat(out).contains("<body>hi</body>");
        assertThat(out).contains("/branding/acme.css");
        assertThat(out).doesNotContain("<title>");
    }

    @Test
    void the_shipped_spa_shell_carries_the_anchors() throws IOException {
        String shell = resource("static/index.html");

        assertThat(shell).contains("data-default-theme=\"");
        assertThat(shell).contains("<title>");
        assertThat(shell).contains("</head>");
        assertThat(BrandingHtml.apply(shell, branding()))
                .contains("<title>ACME Assistants</title>")
                .contains("data-default-theme=\"clody\"")
                .contains("/branding/acme.css");
    }

    @Test
    void the_shipped_login_page_carries_the_anchors() throws IOException {
        String login = resource("static/login.html");

        assertThat(login).contains("<h1 id=\"brand-title\">");
        assertThat(BrandingHtml.apply(login, branding(), "Sign in — ACME Assistants"))
                .contains("<title>Sign in — ACME Assistants</title>")
                .contains("<h1 id=\"brand-title\">ACME Assistants</h1>")
                .contains("/branding/acme.css");
    }

    @Test
    void a_host_whose_picker_is_off_says_so_in_the_shell() {
        BrandingProperties properties = new BrandingProperties();
        properties.getStylePicker().setDisabled(true);

        String out = BrandingHtml.apply(SHELL, properties.resolve());

        assertThat(out).contains("data-theme-picker=\"off\"");
    }

    @Test
    void a_narrowed_picker_names_what_it_may_offer() {
        BrandingProperties properties = new BrandingProperties();
        properties.getStylePicker().setThemes(List.of("amethyst", "default"));

        String out = BrandingHtml.apply(SHELL, properties.resolve());

        assertThat(out).contains("data-theme-picker=\"amethyst default\"");
    }

    @Test
    void the_attribute_is_added_to_a_shell_that_does_not_carry_it_yet() {
        String shell = "<html lang=\"en\"><head><title>x</title></head><body></body></html>";

        String out = BrandingHtml.apply(shell, branding());

        assertThat(out).contains("<html lang=\"en\" data-default-theme=\"clody\" data-theme-picker=\"\">");
    }

    @Test
    void an_attribute_already_there_is_replaced_rather_than_doubled() {
        String out = BrandingHtml.apply(SHELL, branding());

        assertThat(out).contains("data-default-theme=\"clody\"");
        assertThat(out).doesNotContain("data-default-theme=\"amethyst\"");
        assertThat(countOf(out, "data-default-theme")).isEqualTo(1);
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) count++;
        return count;
    }

    private static String resource(String location) throws IOException {
        try (InputStream in = BrandingHtmlTest.class.getClassLoader().getResourceAsStream(location)) {
            assertThat(in).as(location).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
