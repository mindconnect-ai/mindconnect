package ai.mindconnect.llm.adapter.lmstudio;

import ai.mindconnect.llm.domain.LlmCapability;
import ai.mindconnect.llm.domain.LlmConfigType;

import java.util.EnumSet;
import java.util.Set;

/**
 * One model installed in an LM Studio instance, as reported by its native
 * REST API. Carries what the admin UI needs to pick a model and to fill in
 * the config: the id LM Studio wants in {@code model}, the kind (chat or
 * embedding), whether it is currently loaded, and the two context lengths.
 *
 * @param id                  the model key, e.g. {@code openai/gpt-oss-120b}
 * @param displayName         a human-readable name, or the id when LM Studio gives none
 * @param kind                what the model does
 * @param loaded              whether an instance is loaded right now
 * @param maxContextLength    what the model can take, in tokens; null when unknown
 * @param loadedContextLength what the loaded instance was configured with; null when not loaded
 * @param toolUse             whether LM Studio flags the model as trained for tool use
 * @param vision              whether the model takes images
 */
public record LmStudioModel(
        String id,
        String displayName,
        Kind kind,
        boolean loaded,
        Integer maxContextLength,
        Integer loadedContextLength,
        boolean toolUse,
        boolean vision) {

    /** LM Studio's model types, collapsed to what a config cares about. */
    public enum Kind {
        /** Text-only chat model ({@code llm}). */
        LLM,
        /** Chat model that also reads images ({@code vlm}). */
        VLM,
        /** Embedding model ({@code embeddings}). */
        EMBEDDING,
        /** Anything else LM Studio may report in the future. */
        OTHER;

        static Kind parse(String type) {
            if (type == null) return OTHER;
            return switch (type) {
                case "llm" -> LLM;
                case "vlm" -> VLM;
                case "embeddings", "embedding" -> EMBEDDING;
                default -> OTHER;
            };
        }
    }

    /**
     * The context length a config should use: what the model is loaded with
     * when it is loaded (that is the limit requests actually hit), otherwise
     * the model's maximum. Null when LM Studio reports neither.
     */
    public Integer effectiveContextLength() {
        return loadedContextLength != null ? loadedContextLength : maxContextLength;
    }

    /** Whether this model fits a config of the given type. */
    public boolean appliesTo(LlmConfigType type) {
        return type == LlmConfigType.EMBEDDING
                ? kind == Kind.EMBEDDING
                : kind == Kind.LLM || kind == Kind.VLM;
    }

    /** The capabilities LM Studio's metadata vouches for. */
    public Set<LlmCapability> capabilities() {
        Set<LlmCapability> set = EnumSet.noneOf(LlmCapability.class);
        if (toolUse) set.add(LlmCapability.TOOL_CALLING);
        if (vision || kind == Kind.VLM) set.add(LlmCapability.VISION);
        return set;
    }

    /**
     * A one-line label for a dropdown: name, context in thousands of tokens,
     * load state — e.g. {@code GPT-OSS 120B · 32k loaded (max 128k) · tools}.
     */
    public String label() {
        StringBuilder sb = new StringBuilder(displayName == null ? id : displayName);
        if (loaded) {
            sb.append(" · ").append(tokens(loadedContextLength)).append(" loaded");
            if (maxContextLength != null && !maxContextLength.equals(loadedContextLength)) {
                sb.append(" (max ").append(tokens(maxContextLength)).append(")");
            }
        } else if (maxContextLength != null) {
            sb.append(" · max ").append(tokens(maxContextLength));
        }
        if (toolUse) sb.append(" · tools");
        if (vision || kind == Kind.VLM) sb.append(" · vision");
        if (kind == Kind.EMBEDDING) sb.append(" · embedding");
        return sb.toString();
    }

    private static String tokens(Integer n) {
        if (n == null) return "?";
        return n >= 1024 && n % 1024 == 0 ? (n / 1024) + "k" : n.toString();
    }
}
