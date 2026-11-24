package ai.mindconnect.agentrest.service;

import ai.mindconnect.llm.audio.domain.SpeechResult;
import ai.mindconnect.llm.audio.port.in.LlmSpeech;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmConfigId;
import ai.mindconnect.llm.domain.LlmConfigType;
import ai.mindconnect.llm.domain.LlmProvider;
import ai.mindconnect.llm.port.in.LlmEmbeddings;
import ai.mindconnect.llm.port.in.LlmTranscription;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The Test button of a text-to-speech config: audio when it works, a readable error when not. */
class LlmConfigTestServiceSpeechTest {

    private static final LlmConfig TTS = new LlmConfig(LlmConfigId.random(), "text-to-speech", LlmProvider.OPENAI,
            "gpt-4o-mini-tts", "http://localhost", "key", 0.0, 0, Map.of(), null, false, null, null, null,
            LlmConfigType.TEXT_TO_SPEECH, null);

    @Test
    void speaksTheTextAndHandsTheAudioBack() {
        var service = service((name, request) -> new SpeechResult(new byte[2048], "audio/mpeg"));

        var test = service.testSpeech(TTS, "Hallo");

        assertThat(test.result().ok()).isTrue();
        assertThat(test.audio()).hasSize(2048);
        assertThat(test.contentType()).isEqualTo("audio/mpeg");
        assertThat(test.result().finishReason()).isEqualTo("audio/mpeg · 2 KB");
    }

    @Test
    void aProviderFailureIsReportedNotThrown() {
        var service = service((name, request) -> {
            throw new IllegalStateException("Speech call failed (401): invalid key");
        });

        var test = service.testSpeech(TTS, "Hallo");

        assertThat(test.result().ok()).isFalse();
        assertThat(test.result().errorMessage()).contains("401");
        assertThat(test.audio()).isNull();
    }

    @Test
    void withoutASpeechGatewayItSaysSo() {
        var test = service(null).testSpeech(TTS, "Hallo");

        assertThat(test.result().errorMessage()).contains("No speech gateway");
    }

    private static LlmConfigTestService service(LlmSpeech speech) {
        var beans = new StaticListableBeanFactory();
        if (speech != null) beans.addBean("speech", speech);
        ObjectProvider<LlmEmbeddings> embeddings = beans.getBeanProvider(LlmEmbeddings.class);
        ObjectProvider<LlmTranscription> transcription = beans.getBeanProvider(LlmTranscription.class);
        return new LlmConfigTestService(null, embeddings, transcription, beans.getBeanProvider(LlmSpeech.class));
    }
}
