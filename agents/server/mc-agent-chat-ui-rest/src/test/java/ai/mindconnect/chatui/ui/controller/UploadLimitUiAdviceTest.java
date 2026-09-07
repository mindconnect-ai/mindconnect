package ai.mindconnect.chatui.ui.controller;

import ai.mindconnect.agentrest.controller.UploadLimitAdvice;
import ai.mindconnect.ui.model.UiPatch;
import ai.mindconnect.ui.model.UiToast;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The same too-large upload, answered by route: a toast the UI client can
 * show (it only applies a patch from a 2xx), a 413 with a body for the API.
 */
class UploadLimitUiAdviceTest {

    private final MultipartProperties limits = limits();
    private final UploadLimitUiAdvice advice = new UploadLimitUiAdvice(limits, new UploadLimitAdvice(limits));
    private final MaxUploadSizeExceededException tooLarge = new MaxUploadSizeExceededException(-1);

    private static MultipartProperties limits() {
        var props = new MultipartProperties();
        props.setMaxFileSize(DataSize.ofMegabytes(25));
        props.setMaxRequestSize(DataSize.ofMegabytes(100));
        return props;
    }

    @Test
    void aUiUploadGetsAToastOnA200() {
        var request = new MockHttpServletRequest("POST", "/chat/api/sessions/s-1/chat-files");

        ResponseEntity<?> response = advice.tooLarge(tooLarge, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        UiPatch patch = (UiPatch) response.getBody();
        assertThat(patch.getToasts()).hasSize(1);
        UiToast toast = patch.getToasts().get(0);
        assertThat(toast.getLevel()).isEqualTo(UiToast.Level.ERROR);
        assertThat(toast.getTitle()).isEqualTo("Upload failed");
        assertThat(toast.getMessage()).startsWith("Upload too large — at most 25 MB per file and 100 MB per upload.");
    }

    @Test
    void anApiUploadKeepsThe413WithTheJsonBody() {
        var request = new MockHttpServletRequest("POST", "/api/sessions/s-1/files");

        ResponseEntity<?> response = advice.tooLarge(tooLarge, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("maxFileSize", "25 MB");
    }

    @Test
    void theApiIsRecognisedBehindAContextPath() {
        var request = new MockHttpServletRequest("POST", "/mc/api/files");
        request.setContextPath("/mc");
        assertThat(UploadLimitUiAdvice.isApiRoute(request)).isTrue();

        var ui = new MockHttpServletRequest("POST", "/mc/admin/vector-stores/x/upload");
        ui.setContextPath("/mc");
        assertThat(UploadLimitUiAdvice.isApiRoute(ui)).isFalse();
    }
}
