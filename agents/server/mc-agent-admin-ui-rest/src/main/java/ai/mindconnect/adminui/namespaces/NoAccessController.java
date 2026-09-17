package ai.mindconnect.adminui.namespaces;

import ai.mindconnect.adminui.branding.Branding;
import ai.mindconnect.adminui.branding.BrandingProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Objects;

/**
 * What somebody sees who signed in but is in no namespace: this installation
 * has nothing for them yet, and somebody has to invite them.
 *
 * <p>It is a page and not a redirect to the login: they <em>are</em> signed
 * in, and sending them back to the identity provider would loop — it would
 * sign them in again, and they would arrive here again. So the page says what
 * happened, under whose account, and offers the one thing that helps: signing
 * out, so somebody else can sign in on this browser.
 *
 * <p>Standalone HTML, like the login page: no app shell, because the shell is
 * the thing they have no access to. It wears the brand of the host it was
 * asked under, so somebody turned away from a branded host does not suddenly
 * see another name.
 */
@Controller
public class NoAccessController {

    private static final String HTML = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8";

    private final BrandingProperties branding;

    public NoAccessController(BrandingProperties branding) {
        this.branding = Objects.requireNonNull(branding, "branding");
    }

    @GetMapping(value = NamespaceOnboardingFilter.NO_ACCESS, produces = HTML)
    @ResponseBody
    public ResponseEntity<String> noAccess(HttpServletRequest request) {
        Branding brand = branding.resolve(request.getServerName());
        return ResponseEntity.status(403).header("Content-Type", HTML).body(page(brand));
    }

    private static String page(Branding brand) {
        StringBuilder head = new StringBuilder();
        if (brand.favicon() != null) {
            head.append("    <link rel=\"icon\" href=\"").append(escape(brand.favicon())).append("\">\n");
        }
        for (String stylesheet : brand.stylesheets()) {
            head.append("    <link rel=\"stylesheet\" href=\"").append(escape(stylesheet)).append("\">\n");
        }
        String logo = brand.logo() == null ? ""
                : "        <img class=\"mark\" src=\"" + escape(brand.logo()) + "\" alt=\"\">\n";
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>No access — %TITLE%</title>
                    <link rel="stylesheet" href="/sui/sui.css">
                %HEAD%    <style>
                        body { display: flex; align-items: center; justify-content: center;
                               min-height: 100vh; margin: 0;
                               background: var(--sui-color-bg, #fff); color: var(--sui-color-text, #1b2430);
                               font-family: var(--sui-font-family, system-ui, sans-serif); }
                        .card { max-width: 30rem; padding: 2rem; text-align: center;
                                border: 1px solid var(--sui-color-border, #dde3e9);
                                border-radius: var(--sui-radius-lg, 10px);
                                background: var(--sui-color-surface, #fff); }
                        .mark { height: 2.75rem; margin-bottom: 1.15rem; }
                        h1 { font-size: 1.25rem; margin: 0 0 .75rem; color: var(--sui-color-text-strong, #0a192c); }
                        p { margin: 0 0 1rem; line-height: 1.6; color: var(--sui-color-text-body, #3a434c); }
                        a.out { display: inline-block; margin-top: .5rem; padding: .55rem 1.25rem;
                                border-radius: var(--sui-radius-pill, 999px); text-decoration: none;
                                background: var(--sui-color-action, #1b3a6b); color: var(--sui-color-on-action, #fff); }
                    </style>
                </head>
                <body>
                    <main class="card">
                %LOGO%        <h1 id="brand-title">%TITLE%</h1>
                        <p>Your account is signed in, but it is not in any namespace of this
                           installation — so there is nothing here for you to work in yet.</p>
                        <p>Ask an administrator to invite you. They need the e-mail address you
                           sign in with; an invitation is waiting for you the next time you come
                           back, and nothing has to be set up on your side.</p>
                        <a class="out" href="/admin/logout">Sign out</a>
                    </main>
                </body>
                </html>
                """
                .replace("%TITLE%", escape(brand.title()))
                .replace("%HEAD%", head.toString())
                .replace("%LOGO%", logo);
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
