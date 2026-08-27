package ai.mindconnect.llm.domain;

import java.util.List;

/**
 * Supported providers. Each constant also declares the {@link AdditionalParamSpec}s
 * its gateway reads from {@link LlmConfig#additionalParams()} — the single place
 * that documents the generic map, consumed by the admin UI form and the
 * providers endpoint of the REST API.
 */
public enum LlmProvider {
    LM_STUDIO,
    OPENAI,
    AZURE_OPENAI,
    GROQ,
    ANTHROPIC(List.of(
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
    OLLAMA,
    MISTRAL,
    DEEPSEEK,
    TOGETHER,
    OPENROUTER,
    PERPLEXITY,
    FIREWORKS,
    GOOGLE_GEMINI;

    private final List<AdditionalParamSpec> additionalParams;

    LlmProvider() {
        this(List.of());
    }

    LlmProvider(List<AdditionalParamSpec> additionalParams) {
        this.additionalParams = additionalParams;
    }

    /** The additional-parameter fields this provider's gateway understands. */
    public List<AdditionalParamSpec> additionalParams() {
        return additionalParams;
    }

    /** The specs that apply to a config of the given type. */
    public List<AdditionalParamSpec> additionalParams(LlmConfigType type) {
        return additionalParams.stream().filter(spec -> spec.appliesTo(type)).toList();
    }
}
