package ai.mindconnect.adminui.ui;

import ai.mindconnect.adminui.service.TaskMonitor;
import ai.mindconnect.adminui.ui.component.TaskMonitorComponent;
import ai.mindconnect.ui.model.UiPage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

import java.util.Optional;

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
    /** Absent when the host runs no task queue; the header then has no badge. */
    private final TaskMonitor taskMonitor;

    public AdminLayoutFactory(@Value("${mindconnect.auth.enabled:false}") boolean authEnabled,
                              BuildInfo buildInfo,
                              Optional<TaskMonitor> taskMonitor) {
        this.authEnabled = authEnabled;
        this.buildInfo = buildInfo;
        this.taskMonitor = taskMonitor.orElse(null);
    }

    /** Layout for the user currently in the {@link SecurityContextHolder}. */
    public AdminLayout current() {
        if (taskMonitor == null) {
            return new AdminLayout(currentUserName(), authEnabled, buildInfo.label());
        }
        return new AdminLayout(currentUserName(), authEnabled, buildInfo.label(),
                TaskMonitorComponent.badge(taskMonitor.counts()),
                UiPage.ActiveStream.of(TaskMonitor.CHANNEL_ID, TaskMonitorComponent.STREAM_URL,
                        "Task queue", "/admin/agents"));
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
