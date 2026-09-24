package ai.mindconnect.adminui.ui;

import ai.mindconnect.extension.service.ExtensionService;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiPage;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Wraps the pages of extensions in the admin shell. {@link AdminLayoutAdvice}
 * knows the shipped modules by package; an extension's controller lives in
 * a package the host has never heard of, so this one goes by the request:
 * a {@link UiPage} answered on a route an extension's manifest names gets
 * the layout, whatever package served it. An API route
 * ({@code /api/<id>/**}) is not a screen: what it answers goes out as it is.
 */
@ControllerAdvice
public class ExtensionPageAdvice implements ResponseBodyAdvice<Object> {

    private final AdminLayoutFactory layoutFactory;
    private final ExtensionService extensions;

    public ExtensionPageAdvice(AdminLayoutFactory layoutFactory, ExtensionService extensions) {
        this.layoutFactory = layoutFactory;
        this.extensions = extensions;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> converterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (!(body instanceof UiPage page)) return body;
        if (page.getDialogs() != null && !page.getDialogs().isEmpty()) return page;
        UiNode root = page.getNode();
        if (root != null && AdminLayoutAdvice.LAYOUT_ID.equals(root.getId())) return page;
        if (extensions.pageOwner(request.getURI().getPath()).isEmpty()) return page;
        return layoutFactory.current().withLayout(page);
    }
}
