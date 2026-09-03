package ai.mindconnect.adminui.ui;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

/**
 * Builds a per-request {@link AdminLayout} from the current security context.
 *
 * <p>Controllers call {@link #current()} and wrap their page:
 * {@code layoutFactory.current().withLayout(page)}. This keeps the header
 * (brand, nav, user widget, optional logout) consistent across every page
 * without threading the {@code OidcUser} through page constructors.
 */
@Component
public class AdminLayoutFactory {

    private final boolean authEnabled;
    private final BuildInfo buildInfo;

    public AdminLayoutFactory(@Value("${mindconnect.auth.enabled:false}") boolean authEnabled,
                              BuildInfo buildInfo) {
        this.authEnabled = authEnabled;
        this.buildInfo = buildInfo;
    }

    /** Layout for the user currently in the {@link SecurityContextHolder}. */
    public AdminLayout current() {
        return new AdminLayout(currentUserName(), authEnabled, buildInfo.label());
    }

    private String currentUserName() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof OidcUser user) {
            String name = user.getPreferredUsername();
            if (name == null || name.isBlank()) name = user.getFullName();
            if (name != null && !name.isBlank()) return name;
        }
        return "user";
    }
}
