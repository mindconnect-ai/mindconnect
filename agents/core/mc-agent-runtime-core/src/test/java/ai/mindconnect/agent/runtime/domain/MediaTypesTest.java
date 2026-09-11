package ai.mindconnect.agent.runtime.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an upload's media type becomes before a provider sees it: the one
 * spelling the provider takes. A Windows browser reports a {@code .jpg} as
 * {@code image/jpg} or {@code image/pjpeg}, and Anthropic answers that
 * with a 400.
 */
class MediaTypesTest {

    @Test
    void theJpegAliasesFoldOntoImageJpeg() {
        assertThat(MediaTypes.normalize("image/jpg")).isEqualTo("image/jpeg");
        assertThat(MediaTypes.normalize("image/pjpeg")).isEqualTo("image/jpeg");
        assertThat(MediaTypes.normalize("image/jpeg")).isEqualTo("image/jpeg");
    }

    @Test
    void otherAliasesCaseAndParametersAreNormalisedToo() {
        assertThat(MediaTypes.normalize("image/x-png")).isEqualTo("image/png");
        assertThat(MediaTypes.normalize("application/x-pdf")).isEqualTo("application/pdf");
        assertThat(MediaTypes.normalize("Image/PNG")).isEqualTo("image/png");
        assertThat(MediaTypes.normalize("text/plain; charset=UTF-8")).isEqualTo("text/plain");
        assertThat(MediaTypes.normalize(" image/webp ")).isEqualTo("image/webp");
    }

    @Test
    void unknownTypesPassAndNothingStaysNothing() {
        assertThat(MediaTypes.normalize("application/octet-stream")).isEqualTo("application/octet-stream");
        assertThat(MediaTypes.normalize(null)).isNull();
        assertThat(MediaTypes.normalize("")).isEmpty();
    }
}
