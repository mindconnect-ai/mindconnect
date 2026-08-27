package ai.mindconnect.taskqueue.demo;

import ai.mindconnect.ui.model.UiHeader;
import ai.mindconnect.ui.model.UiLink;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiStack;

/**
 * App chrome: header with brand and two nav links (Board, New Task). The
 * wrapped page keeps its navigate target, toasts, dialogs and — critically —
 * its active streams, or the client's stream resume would silently break.
 */
public final class DemoLayout {

    static final String LAYOUT_ID = "taskqueue-demo-layout";

    private DemoLayout() {
    }

    public static UiPage withLayout(UiPage page) {
        var wrapped = UiStack.of(LAYOUT_ID)
                .child(buildHeader(page.getNavigate()))
                .child(page.getNode());
        var result = UiPage.of(page.getNavigate(), wrapped);
        result.setToasts(page.getToasts());
        result.setDialogs(page.getDialogs());
        result.setActiveStreams(page.getActiveStreams());
        return result;
    }

    private static UiHeader buildHeader(String navigate) {
        return UiHeader.of("Task Queue Demo")
                .brandHref("/tasks")
                .extra(navLink("nav-board", "/tasks", "Board", navigate))
                .extra(navLink("nav-new-task", "/tasks/new", "New Task", navigate));
    }

    private static UiLink navLink(String id, String href, String label, String navigate) {
        var link = UiLink.of(id, href, label);
        if (href.equals(navigate)) {
            link.withCssClass("active");
        }
        return link;
    }
}
