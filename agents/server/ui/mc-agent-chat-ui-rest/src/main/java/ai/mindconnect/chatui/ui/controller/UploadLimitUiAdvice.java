package ai.mindconnect.chatui.ui.controller;

import ai.mindconnect.agentrest.controller.UploadLimitAdvice;
import ai.mindconnect.ui.model.UiPatch;
import ai.mindconnect.ui.model.UiToast;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * An upload above the multipart limits, as the UI shows it: a toast that
 * names the limits, in place of the client's generic "The request failed
 * (HTTP 413)". The semantic-ui client applies a {@link UiPatch} only from a
 * 2xx response and ignores the body of any other, so the UI routes answer
 * 200 with the toast; the API routes ({@code /api/**}) keep their 413 with
 * the JSON body of {@link UploadLimitAdvice}.
 *
 * <p>Highest precedence, so on a classpath that carries both advices this
 * one is asked first — the exception is thrown before any controller is
 * chosen, and a package-restricted advice would not apply to it.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class UploadLimitUiAdvice {

    private final MultipartProperties multipart;
    private final UploadLimitAdvice api;

    public UploadLimitUiAdvice(MultipartProperties multipart, UploadLimitAdvice api) {
        this.multipart = multipart;
        this.api = api;
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> tooLarge(MaxUploadSizeExceededException e, HttpServletRequest request) {
        if (isApiRoute(request)) return api.tooLarge(e);
        return ResponseEntity.ok(UiPatch.of().toast(
                UiToast.error(UploadLimitAdvice.message(multipart)).title("Upload failed").sticky()));
    }

    /** The REST API lives under {@code /api/}; everything else on the classpath is a UI route. */
    static boolean isApiRoute(HttpServletRequest request) {
        String path = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && path.startsWith(context)) {
            path = path.substring(context.length());
        }
        return path.startsWith("/api/");
    }
}
