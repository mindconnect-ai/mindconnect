package ai.mindconnect.llm.adapter;

import ai.mindconnect.llm.domain.LlmConfigType;
import ai.mindconnect.llm.domain.LlmProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Reading a provider's model list: the three answer shapes and what they mean. */
class ProviderModelCatalogTest {

    private final ProviderModelCatalog catalog = new ProviderModelCatalog();
    private final ObjectMapper json = new ObjectMapper();

    private com.fasterxml.jackson.databind.JsonNode parse(String body) throws Exception {
        return json.readTree(body);
    }

    @Test
    void anOpenAiCompatibleListingBecomesOptions() throws Exception {
        List<ProviderModelCatalog.Model> models = catalog.parseOpenAi(parse("""
                {"object":"list","data":[
                  {"id":"gpt-4o","object":"model"},
                  {"id":"text-embedding-3-small","object":"model"},
                  {"id":"whisper-1","object":"model"}]}
                """));

        assertThat(models).extracting(ProviderModelCatalog.Model::id)
                .containsExactly("gpt-4o", "text-embedding-3-small", "whisper-1");
        var forChat = new ProviderModelCatalog.Catalog("https://api.openai.com", models, null)
                .forType(LlmConfigType.CHAT);
        assertThat(forChat).extracting(ProviderModelCatalog.Model::id)
                .as("an embedding and a transcription model are not chat models")
                .containsExactly("gpt-4o");
    }

    @Test
    void aListingThatSaysMoreCarriesTheNameAndTheWindow() throws Exception {
        // OpenRouter answers with a display name and the context window.
        List<ProviderModelCatalog.Model> models = catalog.parseOpenAi(parse("""
                {"data":[{"id":"anthropic/claude-sonnet-4.6","name":"Claude Sonnet 4.6",
                          "context_length":200000}]}
                """));

        assertThat(models).singleElement().satisfies(m -> {
            assertThat(m.contextWindowTokens()).isEqualTo(200000);
            assertThat(m.label()).contains("anthropic/claude-sonnet-4.6")
                    .contains("Claude Sonnet 4.6").contains("200k");
        });
    }

    @Test
    void anAnthropicListingIsAllChatModels() throws Exception {
        List<ProviderModelCatalog.Model> models = catalog.parseAnthropic(parse("""
                {"data":[{"type":"model","id":"claude-sonnet-4-6","display_name":"Claude Sonnet 4.6"}]}
                """));

        assertThat(models).singleElement().satisfies(m -> {
            assertThat(m.id()).isEqualTo("claude-sonnet-4-6");
            assertThat(m.type()).isEqualTo(LlmConfigType.CHAT);
            assertThat(m.label()).isEqualTo("claude-sonnet-4-6 · Claude Sonnet 4.6");
        });
    }

    @Test
    void aGeminiListingLosesItsPrefixAndIsSortedByWhatItCanDo() throws Exception {
        List<ProviderModelCatalog.Model> models = catalog.parseGemini(parse("""
                {"models":[
                  {"name":"models/gemini-2.0-flash","displayName":"Gemini 2.0 Flash",
                   "inputTokenLimit":1048576,
                   "supportedGenerationMethods":["generateContent","streamGenerateContent"]},
                  {"name":"models/text-embedding-004","displayName":"Text Embedding 004",
                   "supportedGenerationMethods":["embedContent"]}]}
                """));

        assertThat(models).extracting(ProviderModelCatalog.Model::id)
                .as("a config stores the id without the models/ prefix")
                .containsExactly("gemini-2.0-flash", "text-embedding-004");
        assertThat(models.get(0).type()).isEqualTo(LlmConfigType.CHAT);
        assertThat(models.get(0).contextWindowTokens()).isEqualTo(1048576);
        assertThat(models.get(1).type()).isEqualTo(LlmConfigType.EMBEDDING);
    }

    @Test
    void anUnclassifiedModelIsOfferedForEveryType() {
        var model = ProviderModelCatalog.Model.of("mystral-next", "mystral-next", null);

        assertThat(model.appliesTo(LlmConfigType.CHAT)).isTrue();
        assertThat(model.appliesTo(LlmConfigType.EMBEDDING)).isTrue();
        assertThat(ProviderModelCatalog.typeOf("mistral-embed")).isEqualTo(LlmConfigType.EMBEDDING);
        assertThat(ProviderModelCatalog.typeOf("gpt-4o-transcribe"))
                .isEqualTo(LlmConfigType.SPEECH_TO_TEXT);
        assertThat(ProviderModelCatalog.typeOf("mistral-large-latest")).isNull();
    }

    @Test
    void aBodyThatIsNotAListingIsReportedRatherThanParsedIntoNothing() {
        assertThatThrows(() -> catalog.parseOpenAi(parse("{\"error\":\"nope\"}")));
        assertThatThrows(() -> catalog.parseGemini(parse("{\"data\":[]}")));
    }

    private static void assertThatThrows(org.junit.jupiter.api.function.Executable call) {
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, call);
    }

    @Test
    void everyProviderButLmStudioAndAzureCanBeAsked() {
        assertThat(ProviderModelCatalog.supports(LlmProvider.MISTRAL)).isTrue();
        assertThat(ProviderModelCatalog.supports(LlmProvider.GROQ)).isTrue();
        assertThat(ProviderModelCatalog.supports(LlmProvider.ANTHROPIC)).isTrue();
        assertThat(ProviderModelCatalog.supports(LlmProvider.LM_STUDIO))
                .as("LM Studio has a richer catalog of its own").isFalse();
        assertThat(ProviderModelCatalog.supports(LlmProvider.AZURE_OPENAI))
                .as("Azure configs name deployments, not models").isFalse();
        assertThat(ProviderModelCatalog.supports(null)).isFalse();
    }

    @Test
    void aProviderThatNeedsAKeyIsNotAskedWithoutOne() {
        var answer = catalog.fetch(ai.mindconnect.llm.domain.LlmConfig.mistral("m", "x", null));

        assertThat(answer.available()).isFalse();
        assertThat(answer.error()).contains("no API key");
        assertThat(answer.baseUrl()).isEqualTo("https://api.mistral.ai");
    }

    @Test
    void openRouterAndLocalServersAreAskedWithoutAKey() {
        assertThat(ProviderModelCatalog.needsApiKey(LlmProvider.OPENROUTER))
                .as("OpenRouter's catalog is public").isFalse();
        assertThat(ProviderModelCatalog.needsApiKey(LlmProvider.OLLAMA)).isFalse();
        assertThat(ProviderModelCatalog.needsApiKey(LlmProvider.TOGETHER)).isTrue();
    }

    @Test
    void modelsNoConfigCanCallAreLeftOutOfTheListing() throws Exception {
        List<ProviderModelCatalog.Model> models = catalog.parseOpenAi(parse("""
                {"data":[
                  {"id":"gpt-4o"},{"id":"gpt-4o-mini-tts"},{"id":"tts-1-hd"},{"id":"dall-e-3"},
                  {"id":"gpt-image-1"},{"id":"chatgpt-image-latest"},{"id":"omni-moderation-latest"},
                  {"id":"gpt-realtime"},{"id":"computer-use-preview"},{"id":"babbage-002"},
                  {"id":"davinci-002"},{"id":"gpt-3.5-turbo-instruct"},{"id":"sora-2"},
                  {"id":"gpt-4o-transcribe"},{"id":"text-embedding-3-small"},{"id":"gpt-3.5-turbo"},
                  {"id":"meta-llama/llama-3.3-70b-instruct"},{"id":"mistral-small-latest"}]}
                """));

        assertThat(models).extracting(ProviderModelCatalog.Model::id)
                .as("image, speech, moderation, realtime and legacy completion models are no config's model")
                .containsExactly("gpt-3.5-turbo", "gpt-4o", "gpt-4o-transcribe",
                        "meta-llama/llama-3.3-70b-instruct", "mistral-small-latest", "text-embedding-3-small");

        List<ProviderModelCatalog.Model> gemini = catalog.parseGemini(parse("""
                {"models":[
                  {"name":"models/gemini-2.5-flash","supportedGenerationMethods":["generateContent"]},
                  {"name":"models/gemini-2.5-flash-preview-tts","supportedGenerationMethods":["generateContent"]},
                  {"name":"models/imagen-4.0-generate-001","supportedGenerationMethods":["predict"]},
                  {"name":"models/veo-3.0-generate-001","supportedGenerationMethods":["predictLongRunning"]}]}
                """));
        assertThat(gemini).extracting(ProviderModelCatalog.Model::id).containsExactly("gemini-2.5-flash");
    }

    @Test
    void theBaseUrlIsTakenAsTheProviderRootWhateverWasTyped() {
        assertThat(ProviderModelCatalog.normalise("https://api.mistral.ai/v1/"))
                .isEqualTo("https://api.mistral.ai");
        assertThat(ProviderModelCatalog.normalise("http://localhost:11434/"))
                .isEqualTo("http://localhost:11434");
    }
}
