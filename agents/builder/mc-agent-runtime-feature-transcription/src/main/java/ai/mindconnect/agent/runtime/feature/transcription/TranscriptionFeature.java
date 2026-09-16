package ai.mindconnect.agent.runtime.feature.transcription;

import ai.mindconnect.agent.runtime.feature.FeatureContext;
import ai.mindconnect.agent.runtime.feature.RuntimeFeature;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.llm.adapter.openai.OpenAiTranscriptionGateway;
import ai.mindconnect.llm.port.in.LlmTranscription;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.llm.port.out.TranscriptionGateway;
import ai.mindconnect.llm.service.RoutingLlmTranscriptionService;
import okhttp3.OkHttpClient;

/**
 * Speech to text: transcription by LLM config name, aliases followed, over
 * the OpenAI-compatible transcription endpoint (Whisper, Groq, local
 * servers) — the audio counterpart of the chat routing. Uses the core's
 * HTTP client, mapper, encryption and config repository; a runtime that
 * never hears audio leaves it out.
 */
public class TranscriptionFeature implements RuntimeFeature {

    @Override
    public String name() {
        return "transcription";
    }

    @Override
    public void configure(FeatureContext ctx) {
        ctx.bean(TranscriptionGateway.class, () -> new OpenAiTranscriptionGateway(
                ctx.require(OkHttpClient.class), ctx.objectMapper(), ctx.require(EncryptionHelper.class)));
        ctx.bean(LlmTranscription.class, () -> new RoutingLlmTranscriptionService(
                ctx.require(LlmConfigRepository.class), ctx.require(TranscriptionGateway.class)));
    }
}
