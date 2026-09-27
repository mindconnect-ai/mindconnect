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
 *   <li>{@code UiPatch} and every other non-page body — only a {@link UiPage}
 *       body is handled, so in-place updates pass through untouched.</li>
 *   <li>A page with no node — only dialogs to put over the page that is
 *       there. A page <i>with</i> a node is wrapped even when it opens a
 *       dialog: a link that lands on a folder with a file open is still a
 *       screen, and without the shell the menu was gone once the dialog closed.</li>
 *   <li>Already-wrapped pages — guarded by the {@code admin-layout} root id, so
 *       a controller that delegates to another (which also returns a page)
 *       can't double-wrap.</li>
 * </ul>
 */
@ControllerAdvice(basePackages = {"ai.mindconnect.adminui.ui.controller",
        "ai.mindconnect.chatui.ui.controller", "ai.mindconnect.workflow.admin",
        "ai.mindconnect.mcp.gateway.admin.ui", "ai.mindconnect.agent.registry.admin.ui"})
public class AdminLayoutAdvice implements ResponseBodyAdvice<Object> {

    /** Root-node id used by {@link AdminLayout#withLayout}; marks a wrapped page. */
    static final String LAYOUT_ID = "admin-layout";

    private final AdminLayoutFactory layoutFactory;

    public AdminLayoutAdvice(AdminLayoutFactory layoutFactory) {
        this.layoutFactory = layoutFactory;
    }

    /**
     * Every handler in the advised packages: which responses are pages is
     * decided on the body, in {@link #beforeBodyWrite}. Deciding it here, on
     * the declared return type, is what lost the menu after a save — a save
     * answers a page or a toast patch and is declared {@code ResponseEntity<?>}.
     */
    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends org.springframework.http.converter.HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends org.springframework.http.converter.HttpMessageConverter<?>> converterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (!(body instanceof UiPage page)) {
            return body;
        }
        // Nothing to wrap, or wrapped already. Dialogs travel with the page
        // (AdminLayout.withLayout keeps them), so a page that opens one is
        // wrapped like any other.
        UiNode root = page.getNode();
        if (root == null || LAYOUT_ID.equals(root.getId())) {
            return page;
        }
        return layoutFactory.current().withLayout(page);
    }

}
