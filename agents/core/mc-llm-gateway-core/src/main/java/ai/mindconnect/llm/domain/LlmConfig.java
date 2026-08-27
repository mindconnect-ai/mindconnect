package ai.mindconnect.llm.domain;

import ai.mindconnect.common.util.EnvVarResolver;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

public record LlmConfig(
        UUID id,
        String name,
        LlmProvider provider,
        String model,
        String baseUrl,
        String apiKey,
        double defaultTemperature,
        int maxOutputTokens,
        Map<String, Object> additionalParams,
        /** Maximum context window in tokens. Null means unknown. */
        Integer contextWindowTokens,
        /**
         * When {@code true} this config carries no provider settings of its own and
         * simply points at another config by name via {@link #delegatesTo}. Useful as
         * a stable indirection (e.g. a "default" alias) whose target can be swapped
         * without touching every caller.
         */
        boolean isAlias,
        /** Name of the config this alias delegates to. Only meaningful when {@link #isAlias} is true. */
        String delegatesTo,
        /**
         * Per-config policy for retrying transient provider errors (429/529).
         * {@code null} (the default when a config omits it) means <em>no
         * retry</em> — the call fails fast on the first transient error. Retry
         * happens only when a config explicitly supplies an enabled policy.
         */
        RetryConfig retry,
        /**
         * Per-config concurrency limit for in-flight LLM requests. {@code null}
         * (the default) means <em>no limit</em>. Enforced by
         * {@link ai.mindconnect.llm.service.ThrottlingLlmGateway}. Use it to stay
         * under a provider's rate limit when {@code run_agents} fans out many
         * parallel calls.
         */
        RateLimitConfig rateLimit,
        /**
         * What the model does — {@link LlmConfigType#CHAT} (default) or
         * {@link LlmConfigType#EMBEDDING}. Embedding models turn text into
         * vectors and have no sampling settings — temperature, output tokens,
         * thinking etc. don't apply.
         */
        LlmConfigType type
) {
    /** Normalises {@code null} (legacy configs, terse callers) to CHAT. */
    public LlmConfig {
        if (type == null) type = LlmConfigType.CHAT;
    }

    /** Convenience: is this an embedding model? */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isEmbedding() {
        return type == LlmConfigType.EMBEDDING;
    }

    /** Jackson deserialisation — trailing fields default for old persisted configs. */
    @JsonCreator
    public static LlmConfig fromJson(
            @JsonProperty("id")                   UUID id,
            @JsonProperty("name")                 String name,
            @JsonProperty("provider")             LlmProvider provider,
            @JsonProperty("model")                String model,
            @JsonProperty("baseUrl")              String baseUrl,
            @JsonProperty("apiKey")               String apiKey,
            @JsonProperty("defaultTemperature")   double defaultTemperature,
            @JsonProperty("maxOutputTokens")     int maxOutputTokens,
            @JsonProperty("additionalParams")     Map<String, Object> additionalParams,
            @JsonProperty("contextWindowTokens")  Integer contextWindowTokens,
            @JsonProperty("isAlias")              boolean isAlias,
            @JsonProperty("delegatesTo")          String delegatesTo,
            @JsonProperty("retry")                RetryConfig retry,
            @JsonProperty("rateLimit")            RateLimitConfig rateLimit,
            @JsonProperty("type")                 LlmConfigType type) {
        return new LlmConfig(id, name, provider, model, baseUrl, apiKey,
                defaultTemperature, maxOutputTokens,
                additionalParams != null ? additionalParams : Map.of(),
                contextWindowTokens, isAlias, delegatesTo, retry, rateLimit, type);
    }


    /**
     * Creates an alias config that delegates to another config by name. Aliases
     * carry no provider settings of their own — the routing layer follows
     * {@link #delegatesTo} to the target config at call time.
     */
    public static LlmConfig alias(String name, String delegatesTo) {
        return new LlmConfig(UUID.randomUUID(), name, null, null, null, null,
                0.0, 0, Map.of(), null, true, delegatesTo, null, null, null);
    }

    public static LlmConfig lmStudio(String name, String model, String baseUrl) {
        return new LlmConfig(UUID.randomUUID(), name, LlmProvider.LM_STUDIO,
                model, baseUrl, "lm-studio", 0.7, 2048, Map.of(), 131_072, false, null, null, null, null);
    }

    public static LlmConfig claude(String name, String model, String apiKey) {
        return new LlmConfig(UUID.randomUUID(), name, LlmProvider.ANTHROPIC,
                model, "https://api.anthropic.com", apiKey, 0.7, 8192, Map.of(), 200_000, false, null, null, null, null);
    }

    public static LlmConfig ollama(String name, String model, String baseUrl) {
        return new LlmConfig(UUID.randomUUID(), name, LlmProvider.OLLAMA,
                model, baseUrl, "ollama", 0.7, 4096, Map.of(), null, false, null, null, null, null);
    }

    public static LlmConfig mistral(String name, String model, String apiKey) {
        return new LlmConfig(UUID.randomUUID(), name, LlmProvider.MISTRAL,
                model, "https://api.mistral.ai", apiKey, 0.7, 4096, Map.of(), 128_000, false, null, null, null, null);
    }

    /**
     * @param baseUrl    e.g. {@code https://my-resource.openai.azure.com}
     * @param deployment Azure deployment name (used as the model identifier in the URL)
     */
    public static LlmConfig azureOpenAi(String name, String deployment, String baseUrl, String apiKey) {
        return new LlmConfig(UUID.randomUUID(), name, LlmProvider.AZURE_OPENAI,
                deployment, baseUrl, apiKey, 0.7, 4096, Map.of(), 128_000, false, null, null, null, null);
    }

    public static LlmConfig deepSeek(String name, String model, String apiKey) {
        return new LlmConfig(UUID.randomUUID(), name, LlmProvider.DEEPSEEK,
                model, "https://api.deepseek.com", apiKey, 0.7, 4096, Map.of(), 64_000, false, null, null, null, null);
    }

    public static LlmConfig together(String name, String model, String apiKey) {
        return new LlmConfig(UUID.randomUUID(), name, LlmProvider.TOGETHER,
                model, "https://api.together.xyz", apiKey, 0.7, 4096, Map.of(), null, false, null, null, null, null);
    }

    public static LlmConfig openRouter(String name, String model, String apiKey) {
        return new LlmConfig(UUID.randomUUID(), name, LlmProvider.OPENROUTER,
                model, "https://openrouter.ai/api", apiKey, 0.7, 4096, Map.of(), null, false, null, null, null, null);
    }

    public static LlmConfig perplexity(String name, String model, String apiKey) {
        return new LlmConfig(UUID.randomUUID(), name, LlmProvider.PERPLEXITY,
                model, "https://api.perplexity.ai", apiKey, 0.7, 4096, Map.of(), 128_000, false, null, null, null, null);
    }

    public static LlmConfig fireworks(String name, String model, String apiKey) {
        return new LlmConfig(UUID.randomUUID(), name, LlmProvider.FIREWORKS,
                model, "https://api.fireworks.ai/inference", apiKey, 0.7, 4096, Map.of(), null, false, null, null, null, null);
    }

    public static LlmConfig googleGemini(String name, String model, String apiKey) {
        return new LlmConfig(UUID.randomUUID(), name, LlmProvider.GOOGLE_GEMINI,
                model, "https://generativelanguage.googleapis.com", apiKey, 0.7, 8192, Map.of(), 1_000_000, false, null, null, null, null);
    }

    /** Guards against runaway / circular alias chains. */
    private static final int MAX_ALIAS_DEPTH = 16;

    /**
     * Follows {@link #delegatesTo} until a concrete (non-alias) config is reached,
     * so callers can use a stable alias name (e.g. "default") whose target is swapped
     * centrally. Returns {@code this} when not an alias. Detects cycles and bounds the
     * chain length.
     *
     * @param lookup resolves a config name to a config; must return {@code null} when
     *               the name is unknown (the caller decides how to surface that)
     * @throws IllegalStateException on a missing target, a cycle, or an over-long chain
     */
    public LlmConfig resolveAlias(Function<String, LlmConfig> lookup) {
        LlmConfig config = this;
        Set<String> visited = new LinkedHashSet<>();
        while (config.isAlias()) {
            String target = config.delegatesTo();
            if (target == null || target.isBlank()) {
                throw new IllegalStateException("LLM alias '" + config.name() + "' has no delegatesTo target");
            }
            if (!visited.add(config.name())) {
                throw new IllegalStateException(
                        "Circular LLM alias chain: " + String.join(" → ", visited) + " → " + target);
            }
            if (visited.size() > MAX_ALIAS_DEPTH) {
                throw new IllegalStateException(
                        "LLM alias chain too deep (>" + MAX_ALIAS_DEPTH + "): " + String.join(" → ", visited));
            }
            LlmConfig next = lookup.apply(target);
            if (next == null) {
                throw new IllegalStateException(
                        "LLM alias '" + config.name() + "' delegates to unknown config '" + target + "'");
            }
            config = next;
        }
        return config;
    }

    public LlmConfig withContextWindowTokens(Integer contextWindowTokens) {
        return new LlmConfig(id, name, provider, model, baseUrl, apiKey,
                defaultTemperature, maxOutputTokens, additionalParams, contextWindowTokens, isAlias, delegatesTo, retry, rateLimit, type);
    }

    public LlmConfig withApiKey(String apiKey) {
        return new LlmConfig(id, name, provider, model, baseUrl, apiKey,
                defaultTemperature, maxOutputTokens, additionalParams, contextWindowTokens, isAlias, delegatesTo, retry, rateLimit, type);
    }

    /**
     * Returns a copy with all {@code ${VAR_NAME}} / {@code ${VAR_NAME:default}}
     * placeholders expanded from environment variables across every string field
     * ({@code name}, {@code model}, {@code baseUrl}, {@code apiKey}).
     * Call this at the point of use (e.g. in a gateway), not at save time, so
     * the raw placeholder is preserved in storage.
     */
    public LlmConfig resolved() {
        return new LlmConfig(
                id,
                EnvVarResolver.resolve(name),
                provider,
                EnvVarResolver.resolve(model),
                EnvVarResolver.resolve(baseUrl),
                EnvVarResolver.resolve(apiKey),
                defaultTemperature,
                maxOutputTokens,
                additionalParams,
                contextWindowTokens,
                isAlias,
                delegatesTo,
                retry,
                rateLimit,
                type);
    }

    /**
     * Convenience: resolves env-var placeholders then decrypts the API key via
     * the given {@link ai.mindconnect.common.util.encryption.EncryptionHelper}.
     * Gateways call this once at the top of {@code chatStreaming}.
     */
    public LlmConfig resolved(ai.mindconnect.common.util.encryption.EncryptionHelper encryption) {
        LlmConfig r = resolved();
        return r.withApiKey(encryption.resolve(r.apiKey()));
    }
}
