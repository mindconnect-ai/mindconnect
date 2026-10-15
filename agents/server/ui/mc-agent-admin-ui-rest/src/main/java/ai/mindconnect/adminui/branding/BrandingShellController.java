package ai.mindconnect.adminui.branding;

import ai.mindconnect.ui.assets.SuiAssetRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
 * <p>The branding is resolved per request from the host in the address bar
 * (see {@code mindconnect.branding.switch}), which is why the shell cannot be
 * rendered once at startup: two hosts served by this process get two shells.
 *
 * <p>The file is read per request rather than cached: it is a few kilobytes,
 * asked for once per full page load (the SPA navigates without it), and
 * keeping it uncached means an edited shell shows up on reload the way it did
 * when the resource handler served it.
 *
 * <p>The SPA shell also gets the stylesheets of semantic-ui's asset registry
 * written into its head — ahead of the branding links. The browser module
 * ({@code installAll} from {@code /sui/assets.js}) would link them as well,
 * but at the end of the head, after the branding stylesheets, and an
 * extension's default rule would then win a tie that branding is meant to
 * win. Links already in the page carry {@code data-sui-asset}, so the module
 * leaves them alone.
 */
@Controller
public class BrandingShellController {

    private static final String HTML = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8";

    private static final java.util.regex.Pattern HEAD_END =
            java.util.regex.Pattern.compile("</head>", java.util.regex.Pattern.CASE_INSENSITIVE);

    private final BrandingProperties branding;
    private final ObjectProvider<SuiAssetRegistry> assets;

    public BrandingShellController(BrandingProperties branding) {
        this(branding, null);
    }

    @Autowired
    public BrandingShellController(BrandingProperties branding, ObjectProvider<SuiAssetRegistry> assets) {
        this.branding = branding;
        this.assets = assets;
    }

    /** The SPA shell. */
    @GetMapping(value = "/index.html", produces = HTML)
    @ResponseBody
    public ResponseEntity<String> index(HttpServletRequest request) {
        Branding resolved = branding.resolve(request.getServerName());
        return shell("static/index.html", resolved, resolved.documentTitle(),
                assetLinks(request.getContextPath()));
    }

    /** The login landing page — branded too: it is the first page an installation shows. */
    @GetMapping(value = "/login.html", produces = HTML)
    @ResponseBody
    public ResponseEntity<String> login(HttpServletRequest request) {
        Branding resolved = branding.resolve(request.getServerName());
        return shell("static/login.html", resolved, "Sign in \u2014 " + resolved.title());
    }

    /**
     * The same shell for a page somebody else serves — the "you are in no
     * namespace" page, which needs its own status code and a cookie, and comes
     * through {@code NoAccessController} rather than through a mapping here.
     */
    public String render(String classpathLocation, Branding resolved, String documentTitle) {
        ResponseEntity<String> answer = shell(classpathLocation, resolved, documentTitle);
        return answer.getBody();
    }

    private ResponseEntity<String> shell(String classpathLocation, Branding resolved, String documentTitle) {
        return shell(classpathLocation, resolved, documentTitle, "");
    }

    /** The registry's stylesheet links, or nothing when there is no registry. */
    private String assetLinks(String contextPath) {
        SuiAssetRegistry registry = assets == null ? null : assets.getIfAvailable();
        return registry == null ? "" : registry.headTags(contextPath == null ? "" : contextPath);
    }

    /** {@code html} with {@code links} ahead of {@code </head>}; unchanged when there is no head to anchor on. */
    static String withHeadLinks(String html, String links) {
        if (links == null || links.isEmpty()) return html;
        java.util.regex.Matcher end = HEAD_END.matcher(html);
        if (!end.find()) return html;
        return html.substring(0, end.start()) + "    " + links + "\n" + html.substring(end.start());
    }

    private ResponseEntity<String> shell(String classpathLocation, Branding resolved, String documentTitle,
                                         String assetLinks) {
        Resource resource = new ClassPathResource(classpathLocation);
        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }
        try (var in = resource.getInputStream()) {
            String html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            // The registry's links first, so the branding links BrandingHtml
            // adds before </head> come after them and win.
            return ResponseEntity.ok(BrandingHtml.apply(withHeadLinks(html, assetLinks), resolved, documentTitle));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the UI shell " + classpathLocation, e);
        }
    }
}
