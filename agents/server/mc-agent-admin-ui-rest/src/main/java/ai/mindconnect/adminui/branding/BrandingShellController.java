package ai.mindconnect.adminui.branding;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Serves the two static HTML shells through {@link BrandingHtml}, so the
 * browser tab, the start theme and any branding stylesheet follow
 * {@code mindconnect.branding} the same way the header does.
 *
 * <p>It maps the resources' own URLs ({@code /index.html}, {@code /login.html}),
 * which is what every route into the SPA already forwards to — the SPA
 * controller, the same-URL filter, Spring Security's login page. A controller
 * mapping is consulted before the static resource handler, so nothing else has
 * to know that these two files are no longer served straight off the
 * classpath.
 *
 * <p>The file is read per request rather than cached: it is a few kilobytes,
 * asked for once per full page load (the SPA navigates without it), and
 * keeping it uncached means an edited shell shows up on reload the way it did
 * when the resource handler served it.
 */
@Controller
public class BrandingShellController {

    private static final String HTML = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8";

    private final BrandingProperties branding;

    public BrandingShellController(BrandingProperties branding) {
        this.branding = branding;
    }

    /** The SPA shell. */
    @GetMapping(value = "/index.html", produces = HTML)
    @ResponseBody
    public ResponseEntity<String> index() {
        return shell("static/index.html", branding.getDocumentTitle());
    }

    /** The login landing page — branded too: it is the first page an installation shows. */
    @GetMapping(value = "/login.html", produces = HTML)
    @ResponseBody
    public ResponseEntity<String> login() {
        return shell("static/login.html", "Sign in \u2014 " + branding.getTitle());
    }

    private ResponseEntity<String> shell(String classpathLocation, String documentTitle) {
        Resource resource = new ClassPathResource(classpathLocation);
        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }
        try (var in = resource.getInputStream()) {
            String html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return ResponseEntity.ok(BrandingHtml.apply(html, branding, documentTitle));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the UI shell " + classpathLocation, e);
        }
    }
}
