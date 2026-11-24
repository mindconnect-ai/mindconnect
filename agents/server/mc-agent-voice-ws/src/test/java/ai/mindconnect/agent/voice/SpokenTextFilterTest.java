package ai.mindconnect.agent.voice;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Markdown and control markers are not read aloud. */
class SpokenTextFilterTest {

    @Test
    void dropsMarkdownAndMarkers() {
        assertThat(SpokenTextFilter.spoken("- **Schön**, dass du da warst. [[ENDE]]"))
                .isEqualTo("Schön, dass du da warst.");
        assertThat(SpokenTextFilter.spoken("Siehe [die Seite](https://example.com) und `code`."))
                .isEqualTo("Siehe die Seite und code.");
    }
}
