package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.adminui.ui.AdminPage;
import ai.mindconnect.chatui.ui.controller.FormBody;
import ai.mindconnect.llm.management.api.domain.LlmCapability;
import ai.mindconnect.llm.management.api.domain.LlmConfig;
import ai.mindconnect.llm.management.api.domain.LlmConfigId;
import ai.mindconnect.llm.management.api.domain.LlmConfigType;
import ai.mindconnect.llm.management.api.domain.LlmProvider;
import ai.mindconnect.llm.management.api.domain.RateLimitConfig;
import ai.mindconnect.llm.management.api.domain.RetryConfig;
import ai.mindconnect.llm.gateway.service.LlmConfigService;

import java.util.Map;

/**
 * Reads the LLM config form: what was submitted, as the config to save. One
 * reading for a new config and a changed one — a new one has nothing stored
 * to fall back on, so every field comes from the form or its default.
 */
final class LlmConfigForm {

    /** What the form shows in place of a stored key; sent back unchanged, it means "keep the key". */
    static final String MASKED_KEY = AdminPage.MASKED_VALUE;

    private LlmConfigForm() {
    }

    /**
     * The config the form describes.
     *
     * @param existing the config being edited; {@code null} for a new one
     * @param raw      the submitted form; a field it does not carry keeps the stored value
     */
    static LlmConfig toConfig(LlmConfig existing, Map<String, Object> raw) {
        var body = new FormBody(raw);
        boolean isNew = existing == null;
        boolean isAlias = body.bool("isAlias", !isNew && existing.isAlias());
        // Hidden fields still submit (by design) — an alias must not absorb
        // the invisible provider fields, and vice versa.
        LlmProvider provider = isAlias ? null : requiredProvider(body);
        return new LlmConfig(
                isNew ? LlmConfigId.random() : existing.id(),
                body.str("name"),
                provider,
                isAlias ? null : body.str("model"),
                isAlias ? null : body.str("baseUrl"),
                isAlias ? null : apiKey(existing, provider, body.str("apiKey")),
                body.dbl("defaultTemperature", isNew ? 0.7 : existing.defaultTemperature()),
                body.num("maxOutputTokens", isNew ? 4096 : existing.maxOutputTokens()),
                additionalParamsFrom(body, isNew ? Map.of() : existing.additionalParams(), provider),
                body.numOrNull("contextWindowTokens"),
                isAlias,
                isAlias ? body.str("delegatesTo") : null,
                isNew || raw.containsKey("retryEnabled") ? retryFrom(body) : existing.retry(),
                isNew || raw.containsKey("maxConcurrentRequests") ? rateLimitFrom(body) : existing.rateLimit(),
                isNew || raw.containsKey("type")
                        ? typeFrom(body, isNew ? LlmConfigType.CHAT : existing.type()) : existing.type(),
                isNew || raw.containsKey("capabilities") ? capabilitiesFrom(body) : existing.capabilities(),
                isNew || raw.containsKey("fallbackModels") ? body.strList("fallbackModels") : existing.fallbackModels(),
                null);
    }

    /**
     * The key to store: the form's, or the stored one when the form sent the
     * mask — never the bullets themselves. LM Studio's form has no key field,
     * and a hidden one still submits: for a config being edited the form's
     * key is ignored there, so a config just switched to LM Studio does not
     * carry its old provider's secret to a local server.
     */
    static String apiKey(LlmConfig existing, LlmProvider provider, String formKey) {
        if (existing == null) {
            return LlmConfigService.storedKey(null, provider, MASKED_KEY.equals(formKey) ? null : formKey);
        }
        if (provider == LlmProvider.LM_STUDIO) {
            return LlmConfigService.storedKey(existing, provider, null);
        }
        return MASKED_KEY.equals(formKey) ? existing.apiKey() : formKey;
    }

    /** The form's provider, which a non-alias config must have. */
    static LlmProvider requiredProvider(FormBody body) {
        String raw = body.str("provider");
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Provider is required — pick one before saving");
        }
        return LlmProvider.valueOf(raw);
    }
    /** The form's provider select, tolerant of missing/unknown values. */
    static LlmProvider providerFrom(FormBody body) {
        String raw = body.str("provider");
        if (raw == null || raw.isBlank()) return null;
        try {
            return LlmProvider.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
    /** The form's type select, tolerant of missing/unknown values. */
    static LlmConfigType typeFrom(FormBody body, LlmConfigType fallback) {
        String raw = body.str("type");
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return LlmConfigType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
    /**
     * Builds a {@link RetryConfig} from the flat {@code retry*} form fields.
     * When the "Retry on rate limit" toggle is off, returns {@code null} —
     * which means <em>no retry</em> (the gateway fails fast on 429/529).
     */
    static RetryConfig retryFrom(FormBody body) {
        if (!body.bool("retryEnabled", false)) return null;
        return new RetryConfig(
                true,
                body.num("retryMaxAttempts", RetryConfig.DEFAULT_MAX_ATTEMPTS),
                body.longNum("retryBaseBackoffMillis", RetryConfig.DEFAULT_BASE_BACKOFF_MILLIS),
                body.longNum("retryMaxBackoffMillis", RetryConfig.DEFAULT_MAX_BACKOFF_MILLIS));
    }
    /**
     * Merges the {@code thinking} / {@code effort} form fields into the existing
     * additionalParams, preserving any other provider keys. A blank/absent
     * value removes the key (so "(default)" in the form means "omit it"). Only
     * touches a key when its field is present in the submitted form. Keys are
     * driven by the provider's {@link ai.mindconnect.llm.management.api.domain.AdditionalParamSpec}
     * catalog; specs of OTHER providers are dropped so switching the provider
     * never leaves stale parameters behind. Manually-added keys unknown to any
     * catalog pass through untouched.
     */
    static Map<String, Object> additionalParamsFrom(FormBody body, Map<String, Object> existing,
                                                            LlmProvider provider) {
        Map<String, Object> params = new java.util.HashMap<>(
                existing != null ? existing : Map.of());
        java.util.Set<String> mine = provider == null ? java.util.Set.of()
                : provider.additionalParams().stream()
                        .map(ai.mindconnect.llm.management.api.domain.AdditionalParamSpec::key)
                        .collect(java.util.stream.Collectors.toSet());
        for (LlmProvider p : LlmProvider.values()) {
            for (var spec : p.additionalParams()) {
                if (mine.contains(spec.key())) {
                    applyParam(body, params, spec.key());
                } else {
                    params.remove(spec.key());
                }
            }
        }
        return params;
    }
    static void applyParam(FormBody body, Map<String, Object> params, String key) {
        String v = body.str(key);
        if (v == null) return;            // field absent from form → leave untouched
        if (v.isBlank()) params.remove(key);
        else params.put(key, v);
    }
    /**
     * The capability multiselect as a set — unknown values (a stale form, a
     * renamed constant) are dropped rather than failing the save.
     */
    static java.util.Set<LlmCapability> capabilitiesFrom(FormBody body) {
        java.util.Set<LlmCapability> capabilities = java.util.EnumSet.noneOf(LlmCapability.class);
        for (String raw : body.strList("capabilities")) {
            try {
                capabilities.add(LlmCapability.valueOf(raw.trim()));
            } catch (IllegalArgumentException ignored) {
                // not a capability we know — leave it out
            }
        }
        return capabilities;
    }
    /**
     * Builds a {@link RateLimitConfig} from the {@code maxConcurrentRequests}
     * form field. A blank/absent/≤0 value means no throttling → {@code null}.
     */
    static RateLimitConfig rateLimitFrom(FormBody body) {
        Integer max = body.numOrNull("maxConcurrentRequests");
        if (max == null || max < 1) return null;
        return RateLimitConfig.of(max);
    }
}
