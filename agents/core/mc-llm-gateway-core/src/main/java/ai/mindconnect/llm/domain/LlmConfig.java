package ai.mindconnect.llm.domain;

import ai.mindconnect.common.util.EnvVarResolver;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public record LlmConfig(
        LlmConfigId id,
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
         * What the model does — {@link LlmConfigType#CHAT} (default),
         * {@link LlmConfigType#EMBEDDING} or
         * {@link LlmConfigType#SPEECH_TO_TEXT}. Only chat models have sampling
         * settings; temperature, output tokens, thinking etc. don't apply to
         * the others, and each type is reached through its own port.
         */
        LlmConfigType type,
        /**
         * What the model can take in and do — see {@link LlmCapability}. The
         * runtime reads it: {@code VISION} and {@code DOCUMENTS} decide whether
         * an image or PDF sent with a message reaches the model as content or
         * as a placeholder line. {@code null} means <em>not declared</em> — a
         * config written before the field existed, or one that leaves the
         * decision to the provider — and then {@link LlmProvider#defaultCapabilities()}
         * applies; see {@link #effectiveCapabilities()}. A declared set, the
         * empty set included, always wins over that default.
         */
        Set<LlmCapability> capabilities,
        /**
         * Other configs, <em>by name</em>, to try in order when this one is
         * rate-limited — a provider answering HTTP 429 (rate limit) or 529
         * (overloaded) after this config's own {@link #retry} policy is
         * exhausted. Empty (the default) means <em>no fallback</em>: the
         * transient error propagates to the caller.
         *
         * <p>Followed by {@code ai.mindconnect.llm.service.RoutingLlmChatService}:
         * the first name that still answers serves the request. A fallback may
         * name an alias (it is resolved like any other name) and may point at a
         * different provider entirely — that is the point, a second vendor is
         * not affected by the first one's limit. Only the config the request
         * actually routed to is consulted, so fallback lists are not chained
         * recursively; a name that no config carries is skipped with a warning.
         *
         * <p>A fallback is only taken while nothing has streamed yet — once the
         * first chunk reached the caller, switching models would duplicate a
         * partial answer, so the error propagates instead.
         */
        List<String> fallbackModels,
        /**
         * The stored version this config was read with — optimistic locking for
         * edits made as a whole (the admin form, a REST save). A store saves it only
         * if it is still the stored version and stores it one higher; {@code null}
         * saves without that check. See {@code ai.mindconnect.common.Versions}.
         */
        Long version
) {
    /** Without a version: the config saves without a version check. */
    public LlmConfig(LlmConfigId id, String name, LlmProvider provider, String model, String baseUrl,
                     String apiKey, double defaultTemperature, int maxOutputTokens,
                     Map<String, Object> additionalParams, Integer contextWindowTokens, boolean isAlias,
                     String delegatesTo, RetryConfig retry, RateLimitConfig rateLimit, LlmConfigType type,
                     Set<LlmCapability> capabilities) {
        this(id, name, provider, model, baseUrl, apiKey, defaultTemperature, maxOutputTokens,
                additionalParams, contextWindowTokens, isAlias, delegatesTo, retry, rateLimit, type,
                capabilities, List.of(), null);
    }

    /** This config as read with, or to be saved against, {@code version} — nothing else changes. */
    public LlmConfig withVersion(Long version) {
        return new LlmConfig(id, name, provider, model, baseUrl, apiKey,
                defaultTemperature, maxOutputTokens, additionalParams, contextWindowTokens, isAlias, delegatesTo,
                retry, rateLimit, type, capabilities, fallbackModels, version);
    }

    /**
     * Normalises the type ({@code null} reads as CHAT) and keeps a declared
     * capability set as an unmodifiable copy in declaration order, so JSON
     * and UI show a stable sequence. {@code null} stays {@code null}: not
     * declared is a state of its own.
     */
    public LlmConfig {
        if (type == null) type = LlmConfigType.CHAT;
        if (capabilities != null) {
            capabilities = capabilities.isEmpty() ? Set.of()
                    : Collections.unmodifiableSet(EnumSet.copyOf(capabilities));
        }
        fallbackModels = normaliseFallbacks(fallbackModels);
    }

    /**
     * Fallback names as stored: never null, never blank, trimmed, without
     * duplicates and in the declared order — a form or a hand-written JSON
     * file can carry all of those.
     */
    private static List<String> normaliseFallbacks(List<String> names) {
        if (names == null || names.isEmpty()) return List.of();
        LinkedHashSet<String> cleaned = new LinkedHashSet<>();
        for (String name : names) {
            if (name == null || name.isBlank()) continue;
            cleaned.add(name.trim());
        }
        return List.copyOf(cleaned);
    }

    /** Has this config declared its capabilities itself, rather than leaving them to the provider? */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean declaresCapabilities() {
        return capabilities != null;
    }

    /**
     * The capabilities that hold for this config: the declared set when there
     * is one, else the provider's default, else (no provider — an alias)
     * nothing. What the runtime asks through {@link #supports}.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public Set<LlmCapability> effectiveCapabilities() {
        if (capabilities != null) return capabilities;
        return provider != null ? provider.defaultCapabilities() : Set.of();
    }

    /** Does this config — declared or by its provider's default — have the given capability? */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean supports(LlmCapability capability) {
        return effectiveCapabilities().contains(capability);
    }

    /** Convenience: is this an embedding model? */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isEmbedding() {
        return type == LlmConfigType.EMBEDDING;
    }

    /** Convenience: is this a speech-to-text model? */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isSpeechToText() {
        return type == LlmConfigType.SPEECH_TO_TEXT;
    }

    /** Jackson deserialisation — trailing fields default for old persisted configs. */
    @JsonCreator
    public static LlmConfig fromJson(
            @JsonProperty("id")                   String id,
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
            @JsonProperty("type")                 LlmConfigType type,
            @JsonProperty("capabilities")         Set<LlmCapability> capabilities,
            @JsonProperty("fallbackModels")       List<String> fallbackModels,
            @JsonProperty("version")              Long version) {
        return new LlmConfig(new LlmConfigId(id), name, provider, model, baseUrl, apiKey,
                defaultTemperature, maxOutputTokens,
                additionalParams != null ? additionalParams : Map.of(),
                contextWindowTokens, isAlias, delegatesTo, retry, rateLimit, type, capabilities,
                fallbackModels, version);
    }

    /**
     * Creates an alias config that delegates to another config by name. Aliases
     * carry no provider settings of their own — the routing layer follows
     * {@link #delegatesTo} to the target config at call time.
     */
    public static LlmConfig alias(String name, String delegatesTo) {
        return new LlmConfig(LlmConfigId.random(), name, null, null, null, null,
                0.0, 0, Map.of(), null, true, delegatesTo, null, null, null, null);
    }

    public static LlmConfig lmStudio(String name, String model, String baseUrl) {
        return new LlmConfig(LlmConfigId.random(), name, LlmProvider.LM_STUDIO,
                model, baseUrl, "lm-studio", 0.7, 2048, Map.of(), 131_072, false, null, null, null, null, null);
    }

    public static LlmConfig claude(String name, String model, String apiKey) {
        return new LlmConfig(LlmConfigId.random(), name, LlmProvider.ANTHROPIC,
                model, "https://api.anthropic.com", apiKey, 0.7, 8192, Map.of(), 200_000, false, null, null, null, null, null);
    }

    public static LlmConfig ollama(String name, String model, String baseUrl) {
        return new LlmConfig(LlmConfigId.random(), name, LlmProvider.OLLAMA,
                model, baseUrl, "ollama", 0.7, 4096, Map.of(), null, false, null, null, null, null, null);
    }

    public static LlmConfig mistral(String name, String model, String apiKey) {
        return new LlmConfig(LlmConfigId.random(), name, LlmProvider.MISTRAL,
                model, "https://api.mistral.ai", apiKey, 0.7, 4096, Map.of(), 128_000, false, null, null, null, null, null);
    }

    /**
     * @param baseUrl    e.g. {@code https://my-resource.openai.azure.com}
     * @param deployment Azure deployment name (used as the model identifier in the URL)
     */
    public static LlmConfig azureOpenAi(String name, String deployment, String baseUrl, String apiKey) {
        return new LlmConfig(LlmConfigId.random(), name, LlmProvider.AZURE_OPENAI,
                deployment, baseUrl, apiKey, 0.7, 4096, Map.of(), 128_000, false, null, null, null, null, null);
    }

    public static LlmConfig deepSeek(String name, String model, String apiKey) {
        return new LlmConfig(LlmConfigId.random(), name, LlmProvider.DEEPSEEK,
                model, "https://api.deepseek.com", apiKey, 0.7, 4096, Map.of(), 64_000, false, null, null, null, null, null);
    }

    public static LlmConfig together(String name, String model, String apiKey) {
        return new LlmConfig(LlmConfigId.random(), name, LlmProvider.TOGETHER,
                model, "https://api.together.xyz", apiKey, 0.7, 4096, Map.of(), null, false, null, null, null, null, null);
    }

    public static LlmConfig openRouter(String name, String model, String apiKey) {
        return new LlmConfig(LlmConfigId.random(), name, LlmProvider.OPENROUTER,
                model, "https://openrouter.ai/api", apiKey, 0.7, 4096, Map.of(), null, false, null, null, null, null, null);
    }

    public static LlmConfig perplexity(String name, String model, String apiKey) {
        return new LlmConfig(LlmConfigId.random(), name, LlmProvider.PERPLEXITY,
                model, "https://api.perplexity.ai", apiKey, 0.7, 4096, Map.of(), 128_000, false, null, null, null, null, null);
    }

    public static LlmConfig fireworks(String name, String model, String apiKey) {
        return new LlmConfig(LlmConfigId.random(), name, LlmProvider.FIREWORKS,
                model, "https://api.fireworks.ai/inference", apiKey, 0.7, 4096, Map.of(), null, false, null, null, null, null, null);
    }

    public static LlmConfig googleGemini(String name, String model, String apiKey) {
        return new LlmConfig(LlmConfigId.random(), name, LlmProvider.GOOGLE_GEMINI,
                model, "https://generativelanguage.googleapis.com", apiKey, 0.7, 8192, Map.of(), 1_000_000, false, null, null, null, null, null);
    }

    /**
     * A speech-to-text config for the OpenAI-compatible transcription
     * endpoint: OpenAI itself, Groq, or a local Whisper server behind its own
     * base URL. Sampling settings and the context window stay empty — they do
     * not apply to a transcription call.
     *
     * @param model   e.g. {@code whisper-1} or {@code gpt-4o-mini-transcribe}
     * @param baseUrl the provider root, without the {@code /v1/…} path
     * @param apiKey  the key, or {@code null} for a keyless local server
     */
    public static LlmConfig speechToText(String name, LlmProvider provider, String model,
                                         String baseUrl, String apiKey) {
        return new LlmConfig(LlmConfigId.random(), name, provider, model, baseUrl, apiKey,
                0.0, 0, Map.of(), null, false, null, null, null,
                LlmConfigType.SPEECH_TO_TEXT, null);
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
                defaultTemperature, maxOutputTokens, additionalParams, contextWindowTokens, isAlias, delegatesTo, retry, rateLimit, type, capabilities, fallbackModels, version);
    }

    public LlmConfig withApiKey(String apiKey) {
        return new LlmConfig(id, name, provider, model, baseUrl, apiKey,
                defaultTemperature, maxOutputTokens, additionalParams, contextWindowTokens, isAlias, delegatesTo, retry, rateLimit, type, capabilities, fallbackModels, version);
    }

    /** Returns a copy with the given retry policy ({@code null}: no retry — the call fails fast). */
    public LlmConfig withRetry(RetryConfig retry) {
        return new LlmConfig(id, name, provider, model, baseUrl, apiKey,
                defaultTemperature, maxOutputTokens, additionalParams, contextWindowTokens, isAlias, delegatesTo, retry, rateLimit, type, capabilities, fallbackModels, version);
    }

    /** Returns a copy whose rate-limit fallbacks are exactly the given config names ({@code null}/empty: none). */
    public LlmConfig withFallbackModels(List<String> fallbackModels) {
        return new LlmConfig(id, name, provider, model, baseUrl, apiKey,
                defaultTemperature, maxOutputTokens, additionalParams, contextWindowTokens, isAlias, delegatesTo, retry, rateLimit, type, capabilities, fallbackModels, version);
    }

    /** Does this config name at least one other config to fall back to on a rate limit? */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean hasFallbackModels() {
        return fallbackModels != null && !fallbackModels.isEmpty();
    }

    /** Returns a copy declaring exactly the given capabilities ({@code null}: not declared, the provider's default applies). */
    public LlmConfig withCapabilities(Set<LlmCapability> capabilities) {
        return new LlmConfig(id, name, provider, model, baseUrl, apiKey,
                defaultTemperature, maxOutputTokens, additionalParams, contextWindowTokens, isAlias, delegatesTo, retry, rateLimit, type, capabilities, fallbackModels, version);
    }

    /**
     * Returns a copy with all {@code ${VAR_NAME}} / {@code ${VAR_NAME:default}}
     * placeholders expanded across every string field ({@code name},
     * {@code model}, {@code baseUrl}, {@code apiKey}), from the process
     * environment alone. Call this at the point of use (e.g. in a gateway),
     * not at save time, so the raw placeholder is preserved in storage.
     *
     * <p>Prefer {@link #resolved(java.util.Map)} when the caller has settings of
     * its own to resolve against: this one sees only the process environment.
     */
    public LlmConfig resolved() {
        return resolved(System.getenv());
    }

    /**
     * The same, resolved against {@code env} — the tenant's settings layered
     * over the process environment, in that order. What a tenant sets wins;
     * what only the installation sets still resolves; a placeholder's own
     * {@code :default} is the last word.
     */
    public LlmConfig resolved(java.util.Map<String, String> env) {
        return new LlmConfig(
                id,
                EnvVarResolver.resolve(name, env),
                provider,
                EnvVarResolver.resolve(model, env),
                EnvVarResolver.resolve(baseUrl, env),
                EnvVarResolver.resolve(apiKey, env),
                defaultTemperature,
                maxOutputTokens,
                additionalParams,
                contextWindowTokens,
                isAlias,
                delegatesTo,
                retry,
                rateLimit,
                type,
                capabilities,
                fallbackModels,
                version);
    }

    /**
     * Convenience: resolves env-var placeholders then decrypts the API key via
     * the given {@link ai.mindconnect.common.util.encryption.EncryptionHelper}.
     * Gateways call this once at the top of {@code chatStreaming}.
     */
    public LlmConfig resolved(ai.mindconnect.common.util.encryption.EncryptionHelper encryption) {
        return resolved(System.getenv(), encryption);
    }

    /** Resolves against {@code env}, then decrypts the API key. */
    public LlmConfig resolved(java.util.Map<String, String> env,
                              ai.mindconnect.common.util.encryption.EncryptionHelper encryption) {
        LlmConfig r = resolved(env);
        return r.withApiKey(encryption.resolve(r.apiKey()));
    }
}
