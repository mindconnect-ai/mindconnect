package ai.mindconnect.adminui.ui;

import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiPage;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Wraps every full {@link UiPage} returned by an admin controller with the
 * shared layout (header: brand, nav, user widget, optional logout) — see
 * {@link AdminLayout}. Doing it here, in one place, keeps controllers free of
 * layout concerns and guarantees no page is forgotten.
 *
 * <p>It deliberately does <b>not</b> wrap:
 * <ul>
 *   <li>{@code UiPatch} responses — only {@link UiPage} is handled (the
 *       {@link #supports} check), so in-place updates pass through untouched.</li>
 *   <li>Dialog pages ({@code !page.getDialogs().isEmpty()}) — modals render over
 *       the current page and must not carry their own header.</li>
 *   <li>Already-wrapped pages — guarded by the {@code admin-layout} root id, so
 *       a controller that delegates to another (which also returns a page)
 *       can't double-wrap.</li>
 * </ul>
 */
@ControllerAdvice(basePackages = {"ai.mindconnect.adminui.ui.controller",
        "ai.mindconnect.chatui.ui.controller", "ai.mindconnect.workflow.admin"})
public class AdminLayoutAdvice implements ResponseBodyAdvice<Object> {

    /** Root-node id used by {@link AdminLayout#withLayout}; marks a wrapped page. */
    static final String LAYOUT_ID = "admin-layout";

    private final AdminLayoutFactory layoutFactory;

    public AdminLayoutAdvice(AdminLayoutFactory layoutFactory) {
        this.layoutFactory = layoutFactory;
    }

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends org.springframework.http.converter.HttpMessageConverter<?>> converterType) {
        // Only act on handlers that can produce a UiPage (directly or wrapped in
        // ResponseEntity). UiPatch handlers are skipped entirely.
        return producesUiPage(returnType);
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends org.springframework.http.converter.HttpMessageConverter<?>> converterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (!(body instanceof UiPage page)) {
            return body;
        }
        // Don't wrap modals or already-wrapped pages. A modal page carries its
        // background (already layout-wrapped) as #sui-root and the dialog(s) in
        // page.dialogs, so re-wrapping would double up the header.
        if (page.getDialogs() != null && !page.getDialogs().isEmpty()) {
            return page;
        }
        UiNode root = page.getNode();
        if (root != null && LAYOUT_ID.equals(root.getId())) {
            return page;
        }
        return layoutFactory.current().withLayout(page);
    }

    private static boolean producesUiPage(MethodParameter returnType) {
        Class<?> type = returnType.getParameterType();
        if (UiPage.class.isAssignableFrom(type)) {
            return true;
        }
        // ResponseEntity<UiPage> — inspect the generic argument.
        if (org.springframework.http.ResponseEntity.class.isAssignableFrom(type)) {
            var generic = returnType.getGenericParameterType();
            return generic.getTypeName().contains(UiPage.class.getName());
        }
        return false;
    }
}
