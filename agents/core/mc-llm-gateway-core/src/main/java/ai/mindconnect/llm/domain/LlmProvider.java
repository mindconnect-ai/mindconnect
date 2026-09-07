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
    LM_STUDIO(Set.of(LlmCapability.TOOL_CALLING)),
    OPENAI(Set.of(LlmCapability.TOOL_CALLING, LlmCapability.VISION, LlmCapability.DOCUMENTS)),
    AZURE_OPENAI(Set.of(LlmCapability.TOOL_CALLING, LlmCapability.VISION)),
    GROQ(Set.of(LlmCapability.TOOL_CALLING)),
    ANTHROPIC(Set.of(LlmCapability.TOOL_CALLING, LlmCapability.VISION, LlmCapability.DOCUMENTS), List.of(
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
    OLLAMA(Set.of(LlmCapability.TOOL_CALLING)),
    MISTRAL(Set.of(LlmCapability.TOOL_CALLING)),
    DEEPSEEK(Set.of(LlmCapability.TOOL_CALLING)),
    TOGETHER(Set.of(LlmCapability.TOOL_CALLING)),
    OPENROUTER(Set.of(LlmCapability.TOOL_CALLING)),
    PERPLEXITY(Set.of(LlmCapability.TOOL_CALLING)),
    FIREWORKS(Set.of(LlmCapability.TOOL_CALLING)),
    GOOGLE_GEMINI(Set.of(LlmCapability.TOOL_CALLING, LlmCapability.VISION, LlmCapability.DOCUMENTS,
            LlmCapability.AUDIO_INPUT));

    private final Set<LlmCapability> defaultCapabilities;
    private final List<AdditionalParamSpec> additionalParams;

    LlmProvider(Set<LlmCapability> defaultCapabilities) {
        this(defaultCapabilities, List.of());
    }

    LlmProvider(Set<LlmCapability> defaultCapabilities, List<AdditionalParamSpec> additionalParams) {
        this.defaultCapabilities = defaultCapabilities.isEmpty() ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(defaultCapabilities));
        this.additionalParams = additionalParams;
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

    /** The additional-parameter fields this provider's gateway understands. */
    public List<AdditionalParamSpec> additionalParams() {
        return additionalParams;
    }

    /** The specs that apply to a config of the given type. */
    public List<AdditionalParamSpec> additionalParams(LlmConfigType type) {
        return additionalParams.stream().filter(spec -> spec.appliesTo(type)).toList();
    }
}
