package ai.mindconnect.llm.service;

import ai.mindconnect.common.env.EnvVarResolver;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.llm.management.api.domain.LlmConfig;

/**
 * Expands the placeholders of a config and decrypts its key, at the point of
 * use, so that storage keeps the raw form. Lives outside {@link LlmConfig},
 * which knows neither the environment nor encryption, until the gateway
 * resolves secrets through a port of its own.
 */
public final class LlmConfigResolver {

    private LlmConfigResolver() {
    }

    /**
     * Returns a copy with all {@code ${VAR_NAME}} / {@code ${VAR_NAME:default}}
     * placeholders expanded across every string field ({@code name},
     * {@code model}, {@code baseUrl}, {@code apiKey}), from the process
     * environment alone — the library and desktop case. Call this at the point
     * of use (e.g. in a gateway), not at save time, so the raw placeholder is
     * preserved in storage.
     */
    public static LlmConfig resolved(LlmConfig config) {
        return resolved(config, EnvVarResolver.system());
    }

    /**
     * The same, resolved against {@code env} — on a server the chain of the
     * user's, the namespace's and the process's variables, in that order.
     * The {@code apiKey} takes the first source that has it, the user's own
     * included; {@code name}, {@code model} and {@code baseUrl} resolve from
     * {@link EnvVarResolver#shared()} — the same chain without anybody's
     * personal variables — so a user brings their own key, never their own
     * endpoint for a config that may still carry the installation's key. A
     * placeholder's own {@code :default} is the last word. Each variable is
     * read once, however often the fields refer to it.
     */
    public static LlmConfig resolved(LlmConfig config, EnvVarResolver env) {
        EnvVarResolver secrets = env.memoized();
        EnvVarResolver settings = secrets.shared();
        return new LlmConfig(
                config.id(),
                settings.resolve(config.name()),
                config.provider(),
                resolvedModel(config, secrets),
                settings.resolve(config.baseUrl()),
                secrets.resolve(config.apiKey()),
                config.defaultTemperature(),
                config.maxOutputTokens(),
                config.additionalParams(),
                config.contextWindowTokens(),
                config.isAlias(),
                config.delegatesTo(),
                config.retry(),
                config.rateLimit(),
                config.type(),
                config.capabilities(),
                config.fallbackModels(),
                config.version());
    }

    /**
     * The model a call through this config asks for: {@code model} with its
     * {@code ${VAR}} / {@code ${VAR:default}} placeholders expanded from
     * {@code env}'s {@linkplain EnvVarResolver#shared() shared} sources — the
     * expansion {@link #resolved(LlmConfig, EnvVarResolver)} does, on its own, for a
     * caller that needs the model but not the key (the price dialog).
     *
     * @throws IllegalStateException if a placeholder has no default and no source has the variable
     */
    public static String resolvedModel(LlmConfig config, EnvVarResolver env) {
        return env.shared().resolve(config.model());
    }

    /**
     * Convenience: resolves placeholders from the process environment, then
     * decrypts the API key via the given {@link EncryptionHelper}.
     */
    public static LlmConfig resolved(LlmConfig config, EncryptionHelper encryption) {
        return resolved(config, EnvVarResolver.system(), encryption);
    }

    /**
     * Resolves against {@code env}, then decrypts the API key. Gateways call
     * this once at the top of {@code chatStreaming}, with the
     * {@link EnvVarResolver} they were built with.
     */
    public static LlmConfig resolved(LlmConfig config, EnvVarResolver env, EncryptionHelper encryption) {
        LlmConfig r = resolved(config, env);
        return r.withApiKey(encryption.resolve(r.apiKey()));
    }
}
