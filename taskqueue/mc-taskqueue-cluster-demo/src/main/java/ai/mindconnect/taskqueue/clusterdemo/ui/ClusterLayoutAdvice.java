package ai.mindconnect.taskqueue.clusterdemo.ui;

import ai.mindconnect.taskqueue.clusterdemo.ClusterProperties;
import ai.mindconnect.ui.model.UiHeader;
import ai.mindconnect.ui.model.UiLink;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiStack;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * The cluster app's chrome: same rules as the single-process demo's advice
 * (which is excluded from this app), plus the Dashboard link. Wraps every
 * full {@link UiPage} from the demo UI and the cluster UI alike.
 */
@ControllerAdvice(basePackages = "ai.mindconnect.taskqueue.clusterdemo")
public class ClusterLayoutAdvice implements ResponseBodyAdvice<Object> {

    static final String LAYOUT_ID = "taskqueue-cluster-layout";

    private final ClusterProperties cluster;

    public ClusterLayoutAdvice(ClusterProperties cluster) {
        this.cluster = cluster;
    }

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        Class<?> type = returnType.getParameterType();
        if (UiPage.class.isAssignableFrom(type)) {
            return true;
        }
        if (ResponseEntity.class.isAssignableFrom(type)) {
            return returnType.getGenericParameterType().getTypeName().contains(UiPage.class.getName());
        }
        return false;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> converterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (!(body instanceof UiPage page)) {
            return body;
        }
        if (page.getDialogs() != null && !page.getDialogs().isEmpty()) {
            return page;
        }
        UiNode root = page.getNode();
        if (root != null && LAYOUT_ID.equals(root.getId())) {
            return page;
        }
        var wrapped = UiStack.of(LAYOUT_ID)
                .child(buildHeader(page.getNavigate()))
                .child(page.getNode());
        var result = UiPage.of(page.getNavigate(), wrapped);
        result.setToasts(page.getToasts());
        result.setDialogs(page.getDialogs());
        result.setActiveStreams(page.getActiveStreams());
        return result;
    }

    private UiHeader buildHeader(String navigate) {
        // Say WHO you are looking at — with every node serving the same UI,
        // the brand is the only thing telling a master tab from a worker tab.
        String brand = cluster.isMaster() ? "Task Queue Master"
                : "Task Queue Worker :" + cluster.port();
        return UiHeader.of(brand)
                .brandHref("/tasks/dashboard")
                .extra(navLink("nav-dashboard", "/tasks/dashboard", "Dashboard", navigate))
                .extra(navLink("nav-tasks", "/tasks", "Tasks", navigate))
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
