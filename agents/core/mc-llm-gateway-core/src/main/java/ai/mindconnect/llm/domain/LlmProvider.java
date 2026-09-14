package ai.mindconnect.llm.domain;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Supported providers. Each constant also declares the {@link AdditionalParamSpec}s
 * its gateway reads from {@link LlmConfig#additionalParams()} — the single place
 * that documents the generic map, consumed by the admin UI form and the
 * providers endpoint of the REST API.
 */
public enum LlmProvider {
    LM_STUDIO("http://localhost:1234", Set.of(LlmCapability.TOOL_CALLING), ReasoningParams.CHAT),
    OPENAI("https://api.openai.com",
            Set.of(LlmCapability.TOOL_CALLING, LlmCapability.VISION, LlmCapability.DOCUMENTS),
            ReasoningParams.CHAT_AND_TRANSCRIPTION),
    /** No default: the endpoint is the customer's own resource, {@code https://<resource>.openai.azure.com}. */
    AZURE_OPENAI(null, Set.of(LlmCapability.TOOL_CALLING, LlmCapability.VISION), ReasoningParams.CHAT),
    GROQ("https://api.groq.com/openai", Set.of(LlmCapability.TOOL_CALLING),
            ReasoningParams.CHAT_AND_TRANSCRIPTION),
    ANTHROPIC("https://api.anthropic.com",
            Set.of(LlmCapability.TOOL_CALLING, LlmCapability.VISION, LlmCapability.DOCUMENTS), List.of(
            AdditionalParamSpec.select("thinking", "Thinking",
                    List.of("adaptive", "disabled"),
                    "Anthropic adaptive thinking. 'adaptive' enables reasoning + interleaved "
                            + "thinking (Opus 4.7/4.8). Leave at 'default' to omit.",
                    LlmConfigType.CHAT),
            AdditionalParamSpec.select("effort", "Effort",
                    List.of("low", "medium", "high", "xhigh", "max"),
                    "Reasoning depth / token spend. Only applies when thinking is set. "
                            + "Leave at 'default' to omit.",
                    LlmConfigType.CHAT))),
    OLLAMA("http://localhost:11434", Set.of(LlmCapability.TOOL_CALLING), ReasoningParams.CHAT),
    MISTRAL("https://api.mistral.ai", Set.of(LlmCapability.TOOL_CALLING), ReasoningParams.CHAT),
    DEEPSEEK("https://api.deepseek.com", Set.of(LlmCapability.TOOL_CALLING), ReasoningParams.CHAT),
    TOGETHER("https://api.together.xyz", Set.of(LlmCapability.TOOL_CALLING), ReasoningParams.CHAT),
    OPENROUTER("https://openrouter.ai/api", Set.of(LlmCapability.TOOL_CALLING), ReasoningParams.CHAT),
    PERPLEXITY("https://api.perplexity.ai", Set.of(LlmCapability.TOOL_CALLING), ReasoningParams.CHAT),
    FIREWORKS("https://api.fireworks.ai/inference", Set.of(LlmCapability.TOOL_CALLING), ReasoningParams.CHAT),
    /** xAI, the Grok models. Its API is OpenAI-compatible. */
    XAI("https://api.x.ai", Set.of(LlmCapability.TOOL_CALLING, LlmCapability.VISION), ReasoningParams.CHAT),
    /**
     * Moonshot AI, the Kimi models. OpenAI-compatible; the mainland-China
     * endpoint is {@code https://api.moonshot.cn}, which a config sets as its
     * base URL. Only tool calling by default — the Kimi line is mixed, and a
     * config for a vision model declares {@code VISION} itself.
     */
    MOONSHOT("https://api.moonshot.ai", Set.of(LlmCapability.TOOL_CALLING), ReasoningParams.CHAT),
    GOOGLE_GEMINI("https://generativelanguage.googleapis.com",
            Set.of(LlmCapability.TOOL_CALLING, LlmCapability.VISION, LlmCapability.DOCUMENTS,
            LlmCapability.AUDIO_INPUT));

    private final String defaultBaseUrl;
    private final Set<LlmCapability> defaultCapabilities;
    private final List<AdditionalParamSpec> additionalParams;

    LlmProvider(String defaultBaseUrl, Set<LlmCapability> defaultCapabilities) {
        this(defaultBaseUrl, defaultCapabilities, List.of());
    }

    LlmProvider(String defaultBaseUrl, Set<LlmCapability> defaultCapabilities,
                List<AdditionalParamSpec> additionalParams) {
        this.defaultBaseUrl = defaultBaseUrl;
        this.defaultCapabilities = defaultCapabilities.isEmpty() ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(defaultCapabilities));
        this.additionalParams = additionalParams;
    }

    /**
     * Where this provider's API lives, <em>without</em> the {@code /v1/…} path
     * the adapters append — what a config's {@code baseUrl} should hold when
     * nobody wants to override it. The admin form fills it in when the provider
     * is picked, and the adapters fall back to it for a config that carries
     * none.
     *
     * <p>{@code null} for {@link #AZURE_OPENAI} alone: its endpoint is the
     * customer's own resource and cannot be guessed.
     */
    public String defaultBaseUrl() {
        return defaultBaseUrl;
    }

    /** {@code baseUrl} if it holds anything, else this provider's {@link #defaultBaseUrl()}. */
    public String baseUrlOr(String baseUrl) {
        return baseUrl != null && !baseUrl.isBlank() ? baseUrl.trim() : defaultBaseUrl;
    }

    /**
     * Is this URL just some provider's default rather than an endpoint someone
     * chose? The form uses it to decide whether switching the provider may
     * replace the base URL in place: a default may be overwritten, a hand-typed
     * proxy or local URL may not.
     */
    public static boolean isADefaultBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return true;
        String trimmed = baseUrl.trim();
        for (LlmProvider provider : values()) {
            if (trimmed.equals(provider.defaultBaseUrl)) return true;
        }
        return false;
    }

    /**
     * What a config of this provider is taken to read and do when it declares
     * nothing itself ({@link LlmConfig#capabilities()} is {@code null}): the
     * provider's current mainstream models. Cloud providers whose models all
     * read images and PDFs get {@code VISION} and {@code DOCUMENTS}; hosts of
     * arbitrary models (local servers, routers) only {@code TOOL_CALLING}, since
     * what runs there is anyone's guess — declare it on the config. A declared
     * set, empty included, always wins over this default.
     */
    public Set<LlmCapability> defaultCapabilities() {
        return defaultCapabilities;
    }

    /**
     * The provider as a person picks it from a list. The enum name for most of
     * them — it <em>is</em> the vendor's name — but a vendor whose models go by
     * another name says both, so that somebody looking for Grok or Kimi finds
     * the company that serves it.
     */
    public String label() {
        return switch (this) {
            case XAI -> "XAI (Grok)";
            case MOONSHOT -> "MOONSHOT (Kimi)";
            default -> name();
        };
    }

    /** The additional-parameter fields this provider's gateway understands. */
    public List<AdditionalParamSpec> additionalParams() {
        return additionalParams;
    }

    /** The specs that apply to a config of the given type. */
    public List<AdditionalParamSpec> additionalParams(LlmConfigType type) {
        return additionalParams.stream().filter(spec -> spec.appliesTo(type)).toList();
    }

    /**
     * Holder for the shared parameter lists. An enum constant cannot read a
     * static field of its own enum — the constants are built first — so the
     * lists live in a nested class the constructors may reference.
     */
    private static final class SpeechParams {
        /**
         * The knobs the OpenAI-compatible transcription endpoint understands.
         * Every provider that speaks it shares this list.
         */
        static final List<AdditionalParamSpec> TRANSCRIPTION = List.of(
                AdditionalParamSpec.text("language", "Language",
                        "ISO-639-1 code of the spoken language (de, en, fr). Leave empty to let "
                                + "the model detect it — naming it is faster and more accurate.",
                        LlmConfigType.SPEECH_TO_TEXT),
                AdditionalParamSpec.text("prompt", "Prompt",
                        "Context that steers spelling, e.g. product or people names that occur "
                                + "in the recordings.",
                        LlmConfigType.SPEECH_TO_TEXT),
                AdditionalParamSpec.select("stream", "Stream the transcript",
                        List.of("auto", "off"),
                        "'auto' asks the endpoint to send the text as it forms — models that "
                                + "cannot simply answer in one piece, and callers see no "
                                + "difference. 'off' is for a server that rejects fields it does "
                                + "not know.",
                        LlmConfigType.SPEECH_TO_TEXT),
                AdditionalParamSpec.select("response_format", "Response Format",
                        List.of("json", "verbose_json", "text", "srt", "vtt"),
                        "How the endpoint answers. Leave empty for 'json', which every model "
                                + "takes. 'verbose_json' adds language and duration, and "
                                + "'srt'/'vtt' return subtitles — whisper-1 only; the "
                                + "gpt-4o transcribe models refuse them.",
                        LlmConfigType.SPEECH_TO_TEXT));

        private SpeechParams() {
        }
    }

    /**
     * The reasoning knob every OpenAI-compatible chat endpoint spells the
     * same way: {@code reasoning_effort}. Which levels a server accepts
     * differs — OpenAI's GPT-5 line takes the whole ladder, Groq and xAI a
     * part of it — and a server whose model does not reason ignores the
     * field, so one shared list serves them all.
     */
    private static final class ReasoningParams {
        static final List<AdditionalParamSpec> CHAT = List.of(
                AdditionalParamSpec.select("reasoning_effort", "Reasoning effort",
                        List.of("none", "minimal", "low", "medium", "high", "xhigh"),
                        "How hard a reasoning model thinks before it answers — more effort, "
                                + "slower and better. Which levels the endpoint takes depends on "
                                + "the model; one it does not know it ignores. Leave at "
                                + "'default' to omit.",
                        LlmConfigType.CHAT));

        /** For the providers that also transcribe: the chat knob and the speech ones. */
        static final List<AdditionalParamSpec> CHAT_AND_TRANSCRIPTION = concat(CHAT, SpeechParams.TRANSCRIPTION);

        private static List<AdditionalParamSpec> concat(List<AdditionalParamSpec> a, List<AdditionalParamSpec> b) {
            List<AdditionalParamSpec> all = new java.util.ArrayList<>(a);
            all.addAll(b);
            return List.copyOf(all);
        }

        private ReasoningParams() {
        }
    }
}
