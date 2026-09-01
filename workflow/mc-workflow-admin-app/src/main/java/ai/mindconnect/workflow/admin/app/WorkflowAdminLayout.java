package ai.mindconnect.workflow.admin.app;

import ai.mindconnect.ui.model.UiHeader;
import ai.mindconnect.ui.model.UiLink;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiStack;

/**
 * App-level chrome for the standalone workflow admin: a header with the
 * Mindconnect logo, the "Workflow Admin" brand, a Workflows nav link, and a
 * fixed default user. Lives in the app (not the embeddable rest module) so the
 * rest module stays chrome-free and droppable into other hosts (e.g. the agent
 * UI, which brings its own header).
 */
public final class WorkflowAdminLayout {

    private static final String BASE = "/workflow-admin";
    private final String userName;

    public WorkflowAdminLayout(String userName) {
        this.userName = userName;
    }

    /** Wraps {@code page}'s content with the header, preserving its navigate target. */
    public UiPage withLayout(UiPage page) {
        var wrapped = UiStack.of("workflow-admin-layout")
                .child(buildHeader(page.getNavigate()))
                .child(page.getNode());
        return UiPage.of(page.getNavigate(), wrapped);
    }

    private UiHeader buildHeader(String navigate) {
        var header = UiHeader.of("Workflow Admin")
                .brandHref(BASE)
                .brandLogo("/img/logo.svg")
                .extra(navLink("nav-workflows", BASE, "Workflows", navigate));
        header.user(UiHeader.User.of(userName, initials(userName), null));
        return header;
    }

    private static UiLink navLink(String id, String href, String label, String navigate) {
        var link = UiLink.of(id, href, label);
        if (navigate != null && navigate.startsWith(href)) {
            link.withCssClass("active");
        }
        return link;
    }

    private static String initials(String name) {
        if (name == null || name.isBlank()) return "?";
        String[] parts = name.trim().split("[\\s_.-]+");
        if (parts.length >= 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
            return ("" + parts[0].charAt(0) + parts[1].charAt(0)).toUpperCase();
        }
        return name.substring(0, Math.min(2, name.length())).toUpperCase();
    }
}
