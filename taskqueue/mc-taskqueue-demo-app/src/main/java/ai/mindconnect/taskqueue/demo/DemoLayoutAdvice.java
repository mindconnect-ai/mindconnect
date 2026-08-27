package ai.mindconnect.taskqueue.demo;

import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiPage;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Wraps every full {@link UiPage} with the app chrome ({@link DemoLayout}).
 * Skips {@code UiPatch} responses (not matched by {@code supports}), dialog
 * pages, and already-wrapped pages.
 */
@ControllerAdvice(basePackages = "ai.mindconnect.taskqueue.demo")
public class DemoLayoutAdvice implements ResponseBodyAdvice<Object> {

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
            return page; // modals render over the current page, no header
        }
        UiNode root = page.getNode();
        if (root != null && DemoLayout.LAYOUT_ID.equals(root.getId())) {
            return page; // already wrapped
        }
        return DemoLayout.withLayout(page);
    }
}
