package ai.mindconnect.workflow.admin.app;

import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiPage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Wraps every full {@link UiPage} returned by the embeddable workflow-admin
 * controller with the app's {@link WorkflowAdminLayout} (header + nav + user).
 *
 * <p>Doing it here keeps the rest module chrome-free: it returns bare pages,
 * the app decorates them in one place. Skips {@code UiPatch} responses, dialog
 * pages (modals must not carry a header), and already-wrapped pages.
 */
@ControllerAdvice(basePackages = "ai.mindconnect.workflow.admin")
public class WorkflowAdminLayoutAdvice implements ResponseBodyAdvice<Object> {

    /** Root-node id used by {@link WorkflowAdminLayout}; marks a wrapped page. */
    static final String LAYOUT_ID = "workflow-admin-layout";

    private final String userName;

    public WorkflowAdminLayoutAdvice(
            @Value("${mindconnect.workflow-admin.user:default-user}") String userName) {
        this.userName = userName;
    }

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return producesUiPage(returnType);
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> converterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (!(body instanceof UiPage page)) {
            return body;
        }
        if (page.getDialogs() != null && !page.getDialogs().isEmpty()) {
            return page; // modals render over the current page, no header
        }
        UiNode root = page.getNode();
        if (root != null && LAYOUT_ID.equals(root.getId())) {
            return page; // already wrapped (a controller delegated to another)
        }
        return new WorkflowAdminLayout(userName).withLayout(page);
    }

    private static boolean producesUiPage(MethodParameter returnType) {
        Class<?> type = returnType.getParameterType();
        if (UiPage.class.isAssignableFrom(type)) {
            return true;
        }
        if (ResponseEntity.class.isAssignableFrom(type)) {
            return returnType.getGenericParameterType().getTypeName().contains(UiPage.class.getName());
        }
        return false;
    }
}
