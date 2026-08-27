package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.adminui.ui.UiComponent;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmProvider;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiTrigger;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.ui.model.UiLink;

import java.util.Arrays;
import java.util.List;

/**
 * Edit form for an LLM configuration. Same component for both "new"
 * (null config) and "edit" (existing config) modes — the differences
 * are confined to factory-derived defaults, form id, and the submit
 * target (POST vs PUT).
 */
public final class LlmConfigFormComponent implements UiComponent {

    private final LlmConfig config;
    private final List<LlmConfig> allConfigs;

    /**
     * @param config     the config being edited, or {@code null} for the "new config" form
     * @param allConfigs all stored configs — used to populate the "Delegates To" dropdown
     */
    public LlmConfigFormComponent(LlmConfig config, List<LlmConfig> allConfigs) {
        this.config = config;
        this.allConfigs = allConfigs;
    }

    @Override
    public String id() {
        return config == null ? "llm-config-new" : "llm-config-" + config.id();
    }

    /**
     * The API-key field: a PASSWORD input showing the key as stored
     * ({@code enc:…} stays encrypted; the eye toggle reveals whatever is in
     * the field), with a trailing Encrypt helper that turns a freshly typed
     * plain key into its {@code enc:} form via the server. Also rebuilt by
     * the {@code /encrypt-key} patch endpoint, hence static.
     */
    public static UiField apiKeyField(String value, String formId) {
        return UiField.password("apiKey", "API Key", value)
                .asEditable()
                .hint("Stored as enc:… — type a key and press Encrypt, or use a ${VAR} placeholder. "
                        + "Leave empty for keyless / local endpoints.")
                .trailing(UiAction.secondary("encrypt-key", "Encrypt").icon("lock")
                        .onClick(UiTrigger.api("POST",
                                "/admin/api/llm-configs/encrypt-key?form=" + formId, formId)));
    }

    /**
     * The type-specific settings, swapped in place when the "Embedding Model"
     * checkbox toggles. Chat configs carry the sampling/retry knobs; an
     * embedding model only needs its input window.
     */
    public static ai.mindconnect.ui.model.UiFieldGroup typeGroup(boolean isEmbedding, LlmConfig config) {
        var group = ai.mindconnect.ui.model.UiFieldGroup.of("llm-type-cfg",
                isEmbedding ? "Embedding settings" : "Chat settings");
        if (isEmbedding) {
            group.field(UiField.number("contextWindowTokens", "Max Input Tokens",
                    config == null ? null : config.contextWindowTokens()).asEditable()
                    .hint("Optional — the embedding model's input window, used to size chunks"));
            return group;
        }
        group.field(UiField.number("defaultTemperature", "Temperature",
                        config == null ? 0.7 : config.defaultTemperature()).asEditable())
                .field(UiField.number("maxOutputTokens", "Max Output Tokens",
                        config == null ? 4096 : config.maxOutputTokens()).asEditable())
                .field(UiField.number("contextWindowTokens", "Context Window Tokens",
                        config == null ? null : config.contextWindowTokens()).asEditable()
                        .hint("Optional — used for token budget calculations"))
                .field(UiField.bool("retryEnabled", "Retry on rate limit (429/529)",
                        config != null && config.retry() != null && config.retry().enabled())
                        .asEditable()
                        .hint("If off, requests fail fast on a rate-limit / overload error. Turn on to retry with backoff."))
                .field(UiField.number("retryMaxAttempts", "Retry · Max Attempts",
                        retryInt(config, c -> c.retry().maxAttempts(), 4)).asEditable()
                        .hint("Total tries including the first (e.g. 4 = 1 try + 3 retries). Only used when retry is on."))
                .field(UiField.number("retryBaseBackoffMillis", "Retry · Base Backoff (ms)",
                        retryLong(config, c -> c.retry().baseBackoffMillis(), 2000)).asEditable()
                        .hint("Wait before the first retry; doubles each subsequent attempt."))
                .field(UiField.number("retryMaxBackoffMillis", "Retry · Max Backoff (ms)",
                        retryLong(config, c -> c.retry().maxBackoffMillis(), 30000)).asEditable()
                        .hint("Upper bound for a single backoff wait (a server Retry-After header still wins)."))
                .field(UiField.number("maxConcurrentRequests", "Max Concurrent Requests",
                        config != null && config.rateLimit() != null
                                ? config.rateLimit().maxConcurrentRequests() : null).asEditable()
                        .hint("Caps in-flight LLM requests for this config (across turns, sub-agents, tool loops). Leave empty for unlimited. Use it to stay under a provider's rate limit when run_agents fans out."));
        return group;
    }

    /**
     * The provider-specific additional parameters — rendered straight from the
     * provider's {@link ai.mindconnect.llm.domain.AdditionalParamSpec} catalog,
     * filtered by config type, and swapped in place when provider or type
     * changes. An empty group (provider without extra params) keeps the id
     * anchored for the patch but renders nothing visible.
     */
    public static ai.mindconnect.ui.model.UiFieldGroup providerParamsGroup(
            LlmProvider provider, ai.mindconnect.llm.domain.LlmConfigType type, LlmConfig config) {
        var specs = provider == null
                ? List.<ai.mindconnect.llm.domain.AdditionalParamSpec>of()
                : provider.additionalParams(type);
        var group = ai.mindconnect.ui.model.UiFieldGroup.of("llm-provider-params",
                specs.isEmpty() ? null : provider.name() + " parameters");
        if (specs.isEmpty()) group.hidden();
        for (var spec : specs) {
            String value = param(config, spec.key());
            UiField field = switch (spec.kind()) {
                case SELECT -> {
                    List<UiField.Option> options = new java.util.ArrayList<>();
                    options.add(UiField.Option.of("", "(default)"));
                    spec.options().forEach(o -> options.add(UiField.Option.of(o, o)));
                    yield UiField.select(spec.key(), spec.label(), value, options);
                }
                case NUMBER -> UiField.number(spec.key(), spec.label(),
                        value.isEmpty() ? null : Double.valueOf(value));
                case BOOLEAN -> UiField.bool(spec.key(), spec.label(), Boolean.parseBoolean(value));
                case TEXT -> UiField.text(spec.key(), spec.label(), value.isEmpty() ? null : value);
            };
            group.field(field.asEditable().hint(spec.hint()));
        }
        return group;
    }

    /**
     * Alias mode shows exactly one thing: the delegation target. In provider
     * mode the group is {@code hidden()} — still in the DOM (so the swap
     * patch has its anchor and the value survives), just not visible.
     */
    public static ai.mindconnect.ui.model.UiFieldGroup aliasGroup(
            boolean isAlias, String delegatesTo, java.util.UUID selfId, List<LlmConfig> allConfigs) {
        var group = ai.mindconnect.ui.model.UiFieldGroup.of("llm-alias-cfg", null);
        List<UiField.Option> delegateOptions = allConfigs.stream()
                .filter(c -> selfId == null || !c.id().equals(selfId))
                .map(c -> UiField.Option.of(c.name(), c.name()))
                .toList();
        group.field(UiField.select("delegatesTo", "Delegates To", delegatesTo, delegateOptions)
                .asEditable()
                .hint("The target LLM config this alias points at"));
        return isAlias ? group : group.hidden();
    }

    /**
     * The provider-config fields (type, provider, model, base URL, API key) —
     * hidden entirely in alias mode. Values come from the submitted form when
     * the swap is triggered by a toggle (so typed input survives), from the
     * stored config on first render.
     */
    public static ai.mindconnect.ui.model.UiFieldGroup baseGroup(
            boolean isAlias, String type, String provider, String model, String baseUrl,
            String apiKey, String formId, java.util.UUID configId) {
        var group = ai.mindconnect.ui.model.UiFieldGroup.of("llm-base-cfg", null);
        if (isAlias) group.hidden();
        List<UiField.Option> providerOptions = Arrays.stream(LlmProvider.values())
                .map(p -> UiField.Option.of(p.name(), p.name()))
                .toList();
        String swapUrl = "/admin/api/llm-configs/field-groups?form=" + formId
                + (configId == null ? "" : "&id=" + configId);
        group.field(UiField.select("type", "Type",
                        type == null ? ai.mindconnect.llm.domain.LlmConfigType.CHAT.name() : type,
                        List.of(UiField.Option.of("CHAT", "Chat"),
                                UiField.Option.of("EMBEDDING", "Embedding")))
                        .asEditable()
                        .hint("Embedding models turn text into vectors (vector stores / semantic "
                                + "search); no sampling settings, Test embeds the text instead of chatting.")
                        // Switching swaps the type-specific settings group below.
                        .onChange(UiTrigger.api("POST", swapUrl, formId)))
                .field(UiField.select("provider", "Provider", provider, providerOptions)
                        .asEditable()
                        // Switching re-renders the provider-parameter group below.
                        .onChange(UiTrigger.api("POST", swapUrl, formId)))
                .field(UiField.text("model", "Model", model)
                        .asEditable()
                        .hint("e.g. gpt-4o, gpt-5, claude-sonnet-4-6"))
                .field(UiField.text("baseUrl", "Base URL", baseUrl)
                        .asEditable()
                        .hint("Leave empty to use provider default"))
                .field(apiKeyField(apiKey, formId));
        return group;
    }

    /** Marks the group hidden in alias mode — fields stay in the DOM. */
    public static ai.mindconnect.ui.model.UiFieldGroup withHiddenIf(
            boolean hide, ai.mindconnect.ui.model.UiFieldGroup group) {
        return hide ? group.<ai.mindconnect.ui.model.UiFieldGroup>hidden() : group;
    }

    @Override
    public UiForm render() {
        boolean isNew = config == null;
        boolean isAlias = !isNew && config.isAlias();
        java.util.UUID configId = isNew ? null : config.id();

        return UiForm.of(id(), isNew ? "New LLM Config" : "Edit LLM Config: " + config.name())
                .field(UiField.text("name", "Name", isNew ? null : config.name())
                        .asEditable().asRequired())
                .field(UiField.bool("isAlias", "Is Alias", isAlias)
                        .asEditable()
                        .hint("If set, this config just points at another config by name — handy for a swappable 'default'")
                        // Toggling swaps everything below: alias mode shows only
                        // the delegation target, provider mode everything else.
                        .onChange(UiTrigger.api("POST",
                                "/admin/api/llm-configs/field-groups?form=" + id()
                                        + (isNew ? "" : "&id=" + configId), id())))
                .content(aliasGroup(isAlias, isNew ? null : config.delegatesTo(), configId, allConfigs))
                .content(baseGroup(isAlias,
                        isNew ? null : config.type().name(),
                        isNew || config.provider() == null ? null : config.provider().name(),
                        isNew ? null : config.model(),
                        isNew ? null : config.baseUrl(),
                        isNew ? null : config.apiKey(),
                        id(), configId))
                .content(withHiddenIf(isAlias, typeGroup(!isNew && config.isEmbedding(), config)))
                .content(withHiddenIf(isAlias, providerParamsGroup(
                        isNew ? null : config.provider(),
                        isNew ? ai.mindconnect.llm.domain.LlmConfigType.CHAT : config.type(),
                        config)))
                .action(UiAction.primary("save", "Save").icon("save")
                        .dispatch(isNew ? "POST" : "PUT",
                                  isNew ? "/admin/api/llm-configs"
                                        : "/admin/api/llm-configs/" + config.id(),
                                  id()))
                .action(UiAction.secondary("cancel", "Cancel").icon("cancel")
                        .dispatch("GET", "/admin/api/llm-configs"))
                .link(UiLink.of("back", "/admin/llm-configs", "← Back to LLM Configs"));
    }

    /** Reads a string entry from additionalParams, or "" when absent. */
    private static String param(LlmConfig config, String key) {
        if (config == null || config.additionalParams() == null) return "";
        Object v = config.additionalParams().get(key);
        return v == null ? "" : v.toString();
    }

    /** Existing retry int value if a policy is set, else the placeholder default. */
    private static int retryInt(LlmConfig config,
                                java.util.function.ToIntFunction<LlmConfig> get, int dflt) {
        return (config != null && config.retry() != null) ? get.applyAsInt(config) : dflt;
    }

    /** Existing retry long value if a policy is set, else the placeholder default. */
    private static long retryLong(LlmConfig config,
                                  java.util.function.ToLongFunction<LlmConfig> get, long dflt) {
        return (config != null && config.retry() != null) ? get.applyAsLong(config) : dflt;
    }
}
