package ai.mindconnect.agentrest.controller;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A 413 that says what the limits are, in the units a person reads. */
class UploadLimitAdviceTest {

    private static MultipartProperties limits(DataSize file, DataSize request) {
        var props = new MultipartProperties();
        props.setMaxFileSize(file);
        props.setMaxRequestSize(request);
        return props;
    }

    @Test
    void theBodyNamesBothLimits() {
        var advice = new UploadLimitAdvice(limits(DataSize.ofMegabytes(25), DataSize.ofMegabytes(100)));

        ResponseEntity<Map<String, Object>> response = advice.tooLarge(new MaxUploadSizeExceededException(-1));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody())
                .containsEntry("maxFileSize", "25 MB")
                .containsEntry("maxRequestSize", "100 MB");
        assertThat((String) response.getBody().get("error"))
                .isEqualTo("Upload too large — at most 25 MB per file and 100 MB per upload. "
                        + "Attach fewer files at once, or smaller ones.");
    }

    @Test
    void sizesReadAsPeopleWriteThem() {
        assertThat(UploadLimitAdvice.human(DataSize.ofKilobytes(512))).isEqualTo("512 KB");
        assertThat(UploadLimitAdvice.human(DataSize.ofMegabytes(1))).isEqualTo("1 MB");
        assertThat(UploadLimitAdvice.human(DataSize.ofMegabytes(1536))).isEqualTo("1536 MB");
        assertThat(UploadLimitAdvice.human(DataSize.ofGigabytes(2))).isEqualTo("2 GB");
        assertThat(UploadLimitAdvice.human(DataSize.ofBytes(-1))).isEqualTo("unlimited");
        assertThat(UploadLimitAdvice.human(null)).isEqualTo("unlimited");
    }
}
