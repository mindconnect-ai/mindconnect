package ai.mindconnect.llm.adapter.openai;

/**
 * Known OpenAI model IDs with their API behaviour flags.
 * <p>
 * Reasoning models (o-series) differ from standard chat models:
 * <ul>
 *   <li>Use {@code max_completion_tokens} instead of {@code max_tokens}.</li>
 *   <li>Do not accept a {@code temperature} parameter.</li>
 * </ul>
 * Unknown model strings (e.g. fine-tunes, future releases) fall back to
 * {@link #isReasoningModel(String)}, which uses a name-prefix heuristic.
 * <p>
 * Generated from the OpenAI /v1/models API response. Timestamped variants
 * (e.g. {@code gpt-4o-2024-05-13}) and non-chat models (tts, whisper,
 * dall-e, embeddings, image, audio, realtime, sora) are intentionally omitted.
 */
public enum OpenAiModels {

    // ── Legacy GPT-3.5 ───────────────────────────────────────────────────
    GPT_3_5_TURBO("gpt-3.5-turbo", false),
    GPT_3_5_TURBO_16K("gpt-3.5-turbo-16k", false),
    GPT_3_5_TURBO_INSTRUCT("gpt-3.5-turbo-instruct", false),

    // ── GPT-4 family ─────────────────────────────────────────────────────
    GPT_4("gpt-4", false),
    GPT_4_TURBO("gpt-4-turbo", false),

    // ── GPT-4o family ────────────────────────────────────────────────────
    GPT_4O("gpt-4o", false),
    GPT_4O_MINI("gpt-4o-mini", false),
    GPT_4O_SEARCH_PREVIEW("gpt-4o-search-preview", false),
    GPT_4O_MINI_SEARCH_PREVIEW("gpt-4o-mini-search-preview", false),

    // ── GPT-4.1 family ───────────────────────────────────────────────────
    GPT_4_1("gpt-4.1", false),
    GPT_4_1_MINI("gpt-4.1-mini", false),
    GPT_4_1_NANO("gpt-4.1-nano", false),

    // ── GPT-5 family (reasoning) ─────────────────────────────────────────
    GPT_5("gpt-5", true),
    GPT_5_MINI("gpt-5-mini", true),
    GPT_5_NANO("gpt-5-nano", true),
    GPT_5_PRO("gpt-5-pro", true),
    GPT_5_CODEX("gpt-5-codex", true),
    GPT_5_SEARCH_API("gpt-5-search-api", true),
    GPT_5_CHAT_LATEST("gpt-5-chat-latest", true),

    // ── GPT-5.1 family (reasoning) ───────────────────────────────────────
    GPT_5_1("gpt-5.1", true),
    GPT_5_1_CODEX("gpt-5.1-codex", true),
    GPT_5_1_CODEX_MINI("gpt-5.1-codex-mini", true),
    GPT_5_1_CODEX_MAX("gpt-5.1-codex-max", true),
    GPT_5_1_CHAT_LATEST("gpt-5.1-chat-latest", true),

    // ── GPT-5.2 family (reasoning) ───────────────────────────────────────
    GPT_5_2("gpt-5.2", true),
    GPT_5_2_PRO("gpt-5.2-pro", true),
    GPT_5_2_CODEX("gpt-5.2-codex", true),
    GPT_5_2_CHAT_LATEST("gpt-5.2-chat-latest", true),

    // ── GPT-5.3 family (reasoning) ───────────────────────────────────────
    GPT_5_3_CODEX("gpt-5.3-codex", true),
    GPT_5_3_CHAT_LATEST("gpt-5.3-chat-latest", true),

    // ── GPT-5.4 family (reasoning) ───────────────────────────────────────
    GPT_5_4("gpt-5.4", true),
    GPT_5_4_PRO("gpt-5.4-pro", true),
    GPT_5_4_MINI("gpt-5.4-mini", true),
    GPT_5_4_NANO("gpt-5.4-nano", true),

    // ── GPT-5.5 family (reasoning) ───────────────────────────────────────
    GPT_5_5("gpt-5.5", true),
    GPT_5_5_PRO("gpt-5.5-pro", true),

    // ── o1 family (reasoning) ────────────────────────────────────────────
    O1("o1", true),
    O1_MINI("o1-mini", true),
    O1_PRO("o1-pro", true),

    // ── o3 family (reasoning) ────────────────────────────────────────────
    O3("o3", true),
    O3_MINI("o3-mini", true),

    // ── o4 family (reasoning) ────────────────────────────────────────────
    O4_MINI("o4-mini", true),
    O4_MINI_DEEP_RESEARCH("o4-mini-deep-research", true);

    private final String modelId;
    private final boolean reasoning;

    OpenAiModels(String modelId, boolean reasoning) {
        this.modelId = modelId;
        this.reasoning = reasoning;
    }

    public String modelId() {
        return modelId;
    }

    public boolean isReasoning() {
        return reasoning;
    }

    /**
     * Returns true if the given model ID string represents a reasoning model,
     * either by exact match in this enum or by the o-series name prefix heuristic
     * (covers future model releases and snapshot variants not listed here).
     */
    public static boolean isReasoningModel(String modelId) {
        if (modelId == null) return false;
        for (OpenAiModels m : values()) {
            if (m.modelId.equalsIgnoreCase(modelId)) return m.reasoning;
        }
        // Heuristic fallback for unknown / future models not listed above
        String lower = modelId.toLowerCase();
        return lower.startsWith("o1") || lower.startsWith("o3") || lower.startsWith("o4") || lower.startsWith("o5")
                || lower.startsWith("gpt-5");
    }
}
