package ai.mindconnect.agent.runtime.engine.adapter.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import ai.mindconnect.agent.loop.domain.ContentPart;
import ai.mindconnect.agent.loop.domain.ContentPart.MediaSource;
import ai.mindconnect.agent.loop.domain.TurnMessage;
import ai.mindconnect.agent.runtime.api.domain.ModelRequest;
import ai.mindconnect.agent.runtime.api.domain.ModelSettings;
import ai.mindconnect.agent.runtime.api.domain.TurnId;
import ai.mindconnect.agent.runtime.api.port.out.MediaReader;
import ai.mindconnect.llm.domain.LlmCapability;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmConfigId;
import ai.mindconnect.llm.domain.LlmContent;
import ai.mindconnect.llm.domain.LlmProvider;

/** A stored file in a user's message: shown to a model that reads it, in the turn that brought it. */
class MediaPartsTest {

    private static final ContentPart.Image OWL = new ContentPart.Image(new MediaSource.FileId("f-1"),
            ContentPart.Image.Detail.AUTO, "owl.png");
    private static final MediaReader STORE = file -> "f-1".equals(file.fileId())
            ? Optional.of(new MediaSource.Inline("b3ds", "image/png")) : Optional.empty();
    private static final ModelRequest REQUEST = new ModelRequest(TurnId.of("t2"), ModelSettings.ofModel("model"),
            "You help.", List.of(), List.of());

    private static LlmConfig model(Set<LlmCapability> capabilities) {
        return new LlmConfig(LlmConfigId.random(), "model", LlmProvider.OPENAI, "some-model", "https://example",
                "key", 0.7, 4096, Map.of(), null, false, null, null, null, null, capabilities);
    }

    private static TurnMessage inTurn(String turnId) {
        return TurnMessage.user(List.of(OWL)).inTurn(turnId);
    }

    @Test
    void a_stored_image_of_the_turn_underway_goes_to_a_model_that_reads_images() {
        MediaParts parts = new MediaParts(STORE, model(Set.of(LlmCapability.VISION)), REQUEST);

        assertThat(parts.llmContent(OWL, inTurn("t2"))).isEqualTo(new LlmContent.Image("b3ds", "image/png"));
    }

    @Test
    void a_note_stands_in_for_what_the_model_is_not_shown() {
        MediaParts reads = new MediaParts(STORE, model(Set.of(LlmCapability.VISION)), REQUEST);

        assertThat(text(reads.llmContent(OWL, inTurn("t1")))).as("an earlier turn's")
                .isEqualTo("[System note — image attached: owl.png — sent in an earlier turn, not resent.]");
        assertThat(text(new MediaParts(STORE, model(Set.of()), REQUEST).llmContent(OWL, inTurn("t2"))))
                .contains("this model does not read images");
        assertThat(text(new MediaParts(STORE, null, REQUEST).llmContent(OWL, inTurn("t2"))))
                .as("a model nothing is known of").contains("does not read images");
        assertThat(text(new MediaParts(MediaReader.none(), model(Set.of(LlmCapability.VISION)), REQUEST)
                .llmContent(OWL, inTurn("t2")))).contains("no longer available");
    }

    private static String text(LlmContent content) {
        return ((LlmContent.Text) content).text();
    }
}
