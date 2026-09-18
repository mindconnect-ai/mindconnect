package ai.mindconnect.adminui.namespaces;

import ai.mindconnect.adminui.branding.Branding;
import ai.mindconnect.adminui.branding.BrandingProperties;
import ai.mindconnect.adminui.branding.BrandingShellController;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Objects;

/**
 * What somebody sees who signed in but is in no namespace of this address:
 * this installation has nothing for them yet, and somebody has to invite them.
 *
 * <p>It is a page and not a redirect to the login: they <em>are</em> signed
 * in, and sending them back to the identity provider on its own would loop —
 * it would sign them in again, and they would arrive here again. So the page
 * says what happened first, and offers the one thing that helps: signing out
 * and coming back as somebody else.
 *
 * <p>That offer is one click. It signs them out at the identity provider —
 * without which it would hand the same account straight back — and the way
 * back from there skips the login landing page and shows the provider's own
 * form. The note that says so is a cookie this page sets, good for ten minutes
 * and cleared the moment it is read
 * ({@code SecurityConfig.reloginEntryPoint}).
 *
 * <p>The page itself is {@code static/no-access.html}, served through the same
 * branding as the login page it is the sibling of: same card, same type, same
 * button, and the host's own name, mark and stylesheet. Somebody turned away
 * from a branded host should not suddenly see another name — and should not be
 * able to tell from the design that this page was an afterthought.
 */
@Controller
public class NoAccessController {

    private static final String HTML = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8";

    private final BrandingProperties branding;
    private final BrandingShellController shells;

    public NoAccessController(BrandingProperties branding, BrandingShellController shells) {
        this.branding = Objects.requireNonNull(branding, "branding");
        this.shells = Objects.requireNonNull(shells, "shells");
    }

    @GetMapping(value = NamespaceOnboardingFilter.NO_ACCESS, produces = HTML)
    @ResponseBody
    public ResponseEntity<String> noAccess(HttpServletRequest request, HttpServletResponse response) {
        Branding brand = branding.resolve(request.getServerName());
        response.addCookie(relogin(request));
        String page = shells.render("static/no-access.html", brand, "No access \u2014 " + brand.title());
        return ResponseEntity.status(403).header("Content-Type", HTML).body(page);
    }

    /**
     * The note to the entry point: whoever leaves this page through its one
     * button is coming back to sign in as somebody else, so send them to the
     * provider rather than to the landing page with its "sign in" button.
     */
    private static Cookie relogin(HttpServletRequest request) {
        Cookie cookie = new Cookie(NamespaceOnboardingFilter.RELOGIN_COOKIE, "1");
        cookie.setPath(request.getContextPath().isEmpty() ? "/" : request.getContextPath());
        cookie.setHttpOnly(true);
        cookie.setSecure(request.isSecure());
        cookie.setMaxAge(600);
        cookie.setAttribute("SameSite", "Lax");
        return cookie;
    }
}
