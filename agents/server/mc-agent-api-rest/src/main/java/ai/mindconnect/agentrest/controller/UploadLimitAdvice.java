package ai.mindconnect.agentrest.controller;

import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

/**
 * What an upload above the multipart limits gets instead of a bare 413.
 * Spring throws {@link MaxUploadSizeExceededException} while it parses the
 * request, before any controller is chosen, so only a global advice can
 * answer it; this one keeps the 413 and adds a body that says what the
 * limits are — {@code spring.servlet.multipart.max-file-size} and
 * {@code max-request-size}, {@code MC_UPLOAD_MAX_FILE_SIZE} and
 * {@code MC_UPLOAD_MAX_REQUEST_SIZE} in the apps.
 *
 * <p>Lowest precedence: a UI module on the same classpath (the chat UI)
 * registers an advice of its own that turns the same exception into a
 * toast for the UI routes and delegates here for the API ones.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class UploadLimitAdvice {

    private final MultipartProperties multipart;

    public UploadLimitAdvice(MultipartProperties multipart) {
        this.multipart = multipart;
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> tooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of(
                "error", message(multipart),
                "maxFileSize", human(multipart.getMaxFileSize()),
                "maxRequestSize", human(multipart.getMaxRequestSize())));
    }

    /** The one sentence a person needs: the two limits, and what to do about them. */
    public static String message(MultipartProperties multipart) {
        return "Upload too large — at most " + human(multipart.getMaxFileSize()) + " per file and "
                + human(multipart.getMaxRequestSize()) + " per upload. Attach fewer files at once, or smaller ones.";
    }

    static String human(DataSize size) {
        if (size == null || size.isNegative()) return "unlimited";
        long bytes = size.toBytes();
        if (bytes >= DataSize.ofGigabytes(1).toBytes() && bytes % DataSize.ofGigabytes(1).toBytes() == 0) {
            return size.toGigabytes() + " GB";
        }
        if (bytes >= DataSize.ofMegabytes(1).toBytes()) return size.toMegabytes() + " MB";
        if (bytes >= DataSize.ofKilobytes(1).toBytes()) return size.toKilobytes() + " KB";
        return bytes + " bytes";
    }
}
