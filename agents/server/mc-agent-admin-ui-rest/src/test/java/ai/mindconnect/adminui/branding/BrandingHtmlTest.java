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

    private static BrandingProperties branding() {
        BrandingProperties branding = new BrandingProperties();
        branding.setTitle("ACME Assistants");
        branding.setFavicon("/branding/acme.png");
        branding.setTheme("clody");
        branding.setStylesheets(List.of("/branding/acme.css", "https://cdn.example.com/acme.css"));
        return branding;
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
        BrandingProperties branding = branding();
        branding.setDocumentTitle("ACME Admin");

        String out = BrandingHtml.apply(SHELL, branding);

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
        String out = BrandingHtml.apply(SHELL, new BrandingProperties());

        assertThat(out).contains("<title>Mindconnect Agent Runtime</title>");
        assertThat(out).contains("data-default-theme=\"amethyst\"");
        // The shipped logo doubles as the tab icon; nothing else is added.
        assertThat(out).contains("<link rel=\"icon\" href=\"/img/logo.svg\">");
        assertThat(out).doesNotContain("rel=\"stylesheet\" href=\"/branding");
    }

    @Test
    void a_name_with_markup_in_it_cannot_break_out() {
        BrandingProperties branding = new BrandingProperties();
        branding.setTitle("<script>alert(1)</script>");
        branding.setFavicon("/x.png\" onload=\"alert(1)");

        String out = BrandingHtml.apply(SHELL, branding);

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

    private static String resource(String location) throws IOException {
        try (InputStream in = BrandingHtmlTest.class.getClassLoader().getResourceAsStream(location)) {
            assertThat(in).as(location).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
