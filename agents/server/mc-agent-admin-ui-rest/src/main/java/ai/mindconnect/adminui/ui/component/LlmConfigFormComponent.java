package ai.mindconnect.adminui.ui.component;

import ai.mindconnect.chatui.ui.UiComponent;
import ai.mindconnect.llm.adapter.lmstudio.LmStudioModel;
import ai.mindconnect.llm.adapter.lmstudio.LmStudioModelCatalog;
import ai.mindconnect.llm.domain.LlmCapability;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmConfigType;
import ai.mindconnect.llm.domain.LlmProvider;
import ai.mindconnect.ui.model.UiAction;
import ai.mindconnect.ui.model.UiField;
import ai.mindconnect.ui.model.UiTrigger;
import ai.mindconnect.ui.model.UiForm;
import ai.mindconnect.ui.model.UiLink;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Edit form for an LLM configuration. Same component for both "new"
 * (null config) and "edit" (existing config) modes — the differences
 * are confined to factory-derived defaults, form id, and the submit
 * target (POST vs PUT).
 *
 * <p>For the LM Studio provider the model field is a dropdown of what the
 * LM Studio instance at the base URL has installed, read from its REST API;
 * picking a model fills the context window and the capabilities from what
 * LM Studio reports. Every other provider keeps the free-text model field.
 */
public final class LlmConfigFormComponent implements UiComponent {

    private final LlmConfig config;
    private final List<LlmConfig> allConfigs;
    private final LmStudioModelCatalog.Catalog lmStudio;

    /**
     * @param config     the config being edited, or {@code null} for the "new config" form
     * @param allConfigs all stored configs — used to populate the "Delegates To" dropdown
     */
    public LlmConfigFormComponent(LlmConfig config, List<LlmConfig> allConfigs) {
        this(config, allConfigs, null);
    }

    /**
     * @param lmStudio the LM Studio catalog for the config's base URL — only
     *                 for a config whose provider is LM Studio, {@code null}
     *                 otherwise (the model stays a text field)
     */
    public LlmConfigFormComponent(LlmConfig config, List<LlmConfig> allConfigs,
                                  LmStudioModelCatalog.Catalog lmStudio) {
        this.config = config;
        this.allConfigs = allConfigs;
        this.lmStudio = lmStudio;
    }

    /**
     * What the form fills in after a model was picked from LM Studio's
     * catalog: the context length LM Studio reports for it and the
     * capabilities its metadata vouches for, plus the hint that says where
     * the number came from. {@code null} means "nothing to prefill — show
     * what the config has".
     */
    public record LmStudioPrefill(Integer contextWindowTokens, Set<LlmCapability> capabilities, String hint) {

        public static LmStudioPrefill of(LmStudioModel model) {
            Integer ctx = model.effectiveContextLength();
            String hint;
            if (ctx == null) {
                hint = null;
            } else if (model.loaded()) {
                hint = "From LM Studio: the model is loaded with " + ctx + " tokens";
                if (model.maxContextLength() != null && !model.maxContextLength().equals(ctx)) {
                    hint += " (it takes up to " + model.maxContextLength() + ")";
                }
                hint += ".";
            } else {
                hint = "From LM Studio: the model's maximum, " + ctx + " tokens. It is not loaded "
                        + "right now — LM Studio may load it with a smaller context; lower this if so.";
            }
            return new LmStudioPrefill(ctx, model.capabilities(), hint);
        }
    }

    @Override
    public String id() {
        return config == null ? "llm-config-new" : "llm-config-" + config.id().value();
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
     * The type-specific settings, swapped in place when the type select
     * changes. Chat configs carry the sampling/retry knobs and the capability
     * declaration; an embedding model only needs its input window; a
     * speech-to-text model needs neither — its knobs are the provider
     * parameters below.
     */
    public static ai.mindconnect.ui.model.UiFieldGroup typeGroup(LlmConfigType type, LlmConfig config) {
        return typeGroup(type, config, null, List.of());
    }

    /**
     * @param prefill values picked up from LM Studio for the model just
     *                chosen, or {@code null} to show the config's own
     */
    public static ai.mindconnect.ui.model.UiFieldGroup typeGroup(LlmConfigType type, LlmConfig config,
                                                                 LmStudioPrefill prefill) {
        return typeGroup(type, config, prefill, List.of());
    }

    /**
     * @param allConfigs every stored config — the choices for the fallback
     *                   models a rate-limited call switches to
     */
    public static ai.mindconnect.ui.model.UiFieldGroup typeGroup(LlmConfigType type, LlmConfig config,
                                                                 LmStudioPrefill prefill,
                                                                 List<LlmConfig> allConfigs) {
        var group = ai.mindconnect.ui.model.UiFieldGroup.of("llm-type-cfg", switch (type) {
            case EMBEDDING -> "Embedding settings";
            case SPEECH_TO_TEXT -> "Speech-to-text settings";
            case CHAT -> "Chat settings";
        });
        Integer contextWindow = prefill != null && prefill.contextWindowTokens() != null
                ? prefill.contextWindowTokens()
                : config == null ? null : config.contextWindowTokens();
        String contextHint = prefill != null && prefill.hint() != null ? prefill.hint() : null;
        if (type == LlmConfigType.SPEECH_TO_TEXT) {
            // Nothing of its own: language, prompt and response format are
            // provider parameters and render in the group below.
            return group.hint("A transcription call has no sampling settings. Language, prompt "
                    + "and response format are provider parameters, in the group below.");
        }
        if (type == LlmConfigType.EMBEDDING) {
            group.field(UiField.number("contextWindowTokens", "Max Input Tokens", contextWindow).asEditable()
                    .hint(contextHint != null ? contextHint
                            : "Optional — the embedding model's input window, used to size chunks"));
            return group;
        }
        group.field(capabilitiesField(config, prefill == null ? null : prefill.capabilities()))
                .field(UiField.number("defaultTemperature", "Temperature",
                        config == null ? 0.7 : config.defaultTemperature()).asEditable())
                .field(UiField.number("maxOutputTokens", "Max Output Tokens",
                        config == null ? 4096 : config.maxOutputTokens()).asEditable())
                .field(UiField.number("contextWindowTokens", "Context Window Tokens", contextWindow).asEditable()
                        .hint(contextHint != null ? contextHint : "Optional — used for token budget calculations"))
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
                        .hint("Caps in-flight LLM requests for this config (across turns, sub-agents, tool loops). Leave empty for unlimited. Use it to stay under a provider's rate limit when run_agents fans out."))
                .field(fallbackModelsField(config, allConfigs));
        return group;
    }

    /**
     * The models this config falls back to when the provider rate-limits it:
     * every other config, in the order the admin picks them. Empty means the
     * call fails with the rate-limit error instead of moving on, so the hint
     * says what picking one buys — usually a second vendor, whose limit is a
     * different limit. A name whose config was deleted since stays in the list
     * rather than disappearing silently on the next save.
     */
    private static UiField fallbackModelsField(LlmConfig config, List<LlmConfig> allConfigs) {
        List<String> current = config == null ? List.of() : config.fallbackModels();
        java.util.LinkedHashMap<String, UiField.Option> options = new java.util.LinkedHashMap<>();
        for (LlmConfig other : allConfigs == null ? List.<LlmConfig>of() : allConfigs) {
            if (config != null && other.id().equals(config.id())) continue;
            options.put(other.name(), UiField.Option.of(other.name(), other.name()));
        }
        for (String name : current) {
            options.putIfAbsent(name, UiField.Option.of(name, name + " (no such config)"));
        }
        return UiField.multiselect("fallbackModels", "Fallback models (on rate limit)",
                        current, List.copyOf(options.values()))
                .asEditable()
                .hint("Tried in order when this config is rate-limited (HTTP 429) or the provider is "
                        + "overloaded (529), after its own retries are used up. Pick a config at another "
                        + "provider — its limit is a different limit. Nothing picked: the call fails with "
                        + "the rate-limit error.");
    }

    /**
     * The capability declaration — one option per {@link LlmCapability}, the
     * config's effective set preselected: what it declares, or its provider's
     * default when it declares nothing. Saving the form pins that set on the
     * config; the hint says what Vision and Documents do, so an admin knows
     * that unticking Vision turns images into placeholders. A set picked up
     * from LM Studio's model metadata wins over both.
     */
    private static UiField capabilitiesField(LlmConfig config, Set<LlmCapability> fromLmStudio) {
        List<UiField.Option> options = Arrays.stream(LlmCapability.values())
                .map(c -> UiField.Option.of(c.name(), c.label()))
                .toList();
        List<String> current;
        String origin;
        if (fromLmStudio != null) {
            current = fromLmStudio.stream().map(Enum::name).toList();
            origin = " Preselected from what LM Studio reports for the model; saving pins it.";
        } else {
            current = config == null ? List.of()
                    : config.effectiveCapabilities().stream().map(Enum::name).toList();
            origin = config == null || config.declaresCapabilities() ? ""
                    : " Not declared yet — the preselection is the " + config.provider() + " default; "
                            + "saving pins it.";
        }
        return UiField.multiselect("capabilities", "Capabilities", current, options)
                .asEditable()
                .hint("What the model reads and does. Vision: images sent with a message reach "
                        + "the model as pictures, otherwise as a placeholder line. Documents: the "
                        + "same for PDFs. Tool calling and audio are declarative for now." + origin);
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
            boolean isAlias, String delegatesTo, ai.mindconnect.llm.domain.LlmConfigId selfId, List<LlmConfig> allConfigs) {
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
     *
     * <p>LM Studio is the one provider whose server can be asked what it
     * offers, so its base group differs: the model is a dropdown of the
     * installed models (a text field with a hint when LM Studio does not
     * answer), the base URL defaults to LM Studio's port and reloads the list
     * when changed, and there is no API-key field — a local LM Studio takes
     * no key.
     *
     * @param lmStudio the catalog read from the base URL when the provider is
     *                 LM Studio; {@code null} for every other provider
     */
    public static ai.mindconnect.ui.model.UiFieldGroup baseGroup(
            boolean isAlias, String type, String provider, String model, String baseUrl,
            String apiKey, String formId, ai.mindconnect.llm.domain.LlmConfigId configId,
            LmStudioModelCatalog.Catalog lmStudio) {
        var group = ai.mindconnect.ui.model.UiFieldGroup.of("llm-base-cfg", null);
        if (isAlias) group.hidden();
        boolean isLmStudio = LlmProvider.LM_STUDIO.name().equals(provider);
        if (isLmStudio && (baseUrl == null || baseUrl.isBlank())) {
            baseUrl = LmStudioModelCatalog.DEFAULT_BASE_URL;
        }
        LlmConfigType configType = parseType(type);
        // A new config starts with no provider: the picker must not appear for
        // a provider nobody chose, so the first option is a blank the admin
        // has to move off before saving.
        List<UiField.Option> providerOptions = new ArrayList<>();
        if (provider == null) providerOptions.add(UiField.Option.of("", "— pick a provider —"));
        Arrays.stream(LlmProvider.values())
                .map(p -> UiField.Option.of(p.name(), p.name()))
                .forEach(providerOptions::add);
        String swapUrl = "/admin/api/llm-configs/field-groups?form=" + formId
                + (configId == null ? "" : "&id=" + configId.value());
        group.field(UiField.select("type", "Type",
                        type == null ? LlmConfigType.CHAT.name() : type,
                        List.of(UiField.Option.of("CHAT", "Chat"),
                                UiField.Option.of("EMBEDDING", "Embedding"),
                                UiField.Option.of("SPEECH_TO_TEXT", "Speech to text")))
                        .asEditable()
                        .hint("Embedding models turn text into vectors (vector stores / semantic "
                                + "search); speech-to-text models turn a recording into text. "
                                + "Neither has sampling settings, and Test does the matching thing "
                                + "instead of chatting.")
                        // Switching swaps the type-specific settings group below.
                        .onChange(UiTrigger.api("POST", swapUrl, formId)))
                .field(UiField.select("provider", "Provider", provider == null ? "" : provider, providerOptions)
                        .asEditable().asRequired()
                        // Switching re-renders the provider-parameter group below.
                        .onChange(UiTrigger.api("POST", swapUrl, formId)))
                .field(modelField(model, isLmStudio ? lmStudio : null, configType, swapUrl, formId));
        if (isLmStudio) {
            group.field(UiField.text("baseUrl", "Base URL", baseUrl)
                    .asEditable()
                    .hint("The LM Studio server. Its models are read from " + baseUrl
                            + "/api/v0/models — change the URL to reload the list.")
                    // A different server has different models.
                    .onChange(UiTrigger.api("POST", swapUrl, formId)));
        } else {
            group.field(UiField.text("baseUrl", "Base URL", baseUrl)
                            .asEditable()
                            .hint("Leave empty to use provider default"))
                    .field(apiKeyField(apiKey, formId));
        }
        return group;
    }

    /**
     * The model field. A text field for every provider but LM Studio; for LM
     * Studio a dropdown of the models its server lists for this config type
     * (chat models for a chat config, embedding models for an embedding
     * config). A stored model the server no longer has stays selectable, so
     * opening the form never silently drops it. When the server does not
     * answer, the text field returns with the reason as its hint.
     */
    static UiField modelField(String model, LmStudioModelCatalog.Catalog lmStudio, LlmConfigType type,
                              String swapUrl, String formId) {
        if (lmStudio == null) {
            return UiField.text("model", "Model", model)
                    .asEditable()
                    .hint(type == LlmConfigType.SPEECH_TO_TEXT
                            ? "e.g. whisper-1, gpt-4o-transcribe, gpt-4o-mini-transcribe"
                            : "e.g. gpt-4o, gpt-5, claude-sonnet-4-6");
        }
        if (!lmStudio.available()) {
            return UiField.text("model", "Model", model)
                    .asEditable()
                    .hint("LM Studio at " + lmStudio.baseUrl() + " did not answer (" + lmStudio.error()
                            + "). Type the model id, or start LM Studio and re-enter the Base URL "
                            + "to load the list.");
        }
        List<UiField.Option> options = new ArrayList<>();
        options.add(UiField.Option.of("", "— pick a model —"));
        boolean listed = false;
        for (LmStudioModel m : lmStudio.models()) {
            if (!m.appliesTo(type)) continue;
            options.add(UiField.Option.of(m.id(), m.label()));
            listed |= m.id().equals(model);
        }
        boolean hasModel = model != null && !model.isBlank();
        if (hasModel && !listed) {
            options.add(UiField.Option.of(model, model + " (not installed in LM Studio)"));
        }
        String what = switch (type) {
            case EMBEDDING -> "embedding";
            case SPEECH_TO_TEXT -> "speech-to-text";
            case CHAT -> "chat";
        };
        String hint = options.size() == 1
                ? "LM Studio at " + lmStudio.baseUrl() + " lists no " + what + " models."
                : "Installed in LM Studio at " + lmStudio.baseUrl()
                        + ". Picking a model fills in its context window below.";
        return UiField.select("model", "Model", hasModel ? model : "", options)
                .asEditable()
                .hint(hint)
                // The pick decides the context window: re-render with reason=model.
                .onChange(UiTrigger.api("POST", swapUrl + "&reason=model", formId));
    }

    /** The form's type value as an enum; anything unknown reads as chat. */
    private static LlmConfigType parseType(String type) {
        if (type == null || type.isBlank()) return LlmConfigType.CHAT;
        try {
            return LlmConfigType.valueOf(type);
        } catch (IllegalArgumentException e) {
            return LlmConfigType.CHAT;
        }
    }

    /** Marks the group hidden in alias mode — fields stay in the DOM. */
    public static ai.mindconnect.ui.model.UiFieldGroup withHiddenIf(
            boolean hide, ai.mindconnect.ui.model.UiFieldGroup group) {
        return hide ? group.<ai.mindconnect.ui.model.UiFieldGroup>hidden() : group;
    }

    /**
     * The name field — also rebuilt by the field-groups patch when a model
     * picked from LM Studio names a config that has no name yet.
     */
    public static UiField nameField(String name) {
        return UiField.text("name", "Name", name).asEditable().asRequired();
    }

    /**
     * A config name suggested by a model id: the last path segment, so
     * {@code openai/gpt-oss-120b} becomes {@code gpt-oss-120b} — config names
     * travel in URLs and agent definitions, where a slash would get in the way.
     */
    public static String suggestedName(String modelId) {
        if (modelId == null || modelId.isBlank()) return null;
        String id = modelId.trim();
        int slash = id.lastIndexOf('/');
        String name = slash >= 0 && slash < id.length() - 1 ? id.substring(slash + 1) : id;
        return name.isBlank() ? null : name;
    }

    @Override
    public UiForm render() {
        boolean isNew = config == null;
        boolean isAlias = !isNew && config.isAlias();
        ai.mindconnect.llm.domain.LlmConfigId configId = isNew ? null : config.id();

        return UiForm.of(id(), isNew ? "New LLM Config" : "Edit LLM Config: " + config.name())
                .field(nameField(isNew ? null : config.name()))
                // The version this form was opened with — outside the groups the
                // alias toggle swaps. Hidden, but submitted: the save is refused if
                // the config was saved since.
                .field(UiField.text("version", "Version",
                                isNew || config.version() == null ? "0" : config.version().toString())
                        .asEditable().<UiField>hidden())
                .field(UiField.bool("isAlias", "Is Alias", isAlias)
                        .asEditable()
                        .hint("If set, this config just points at another config by name — handy for a swappable 'default'")
                        // Toggling swaps everything below: alias mode shows only
                        // the delegation target, provider mode everything else.
                        .onChange(UiTrigger.api("POST",
                                "/admin/api/llm-configs/field-groups?form=" + id()
                                        + (isNew ? "" : "&id=" + configId.value()), id())))
                .content(aliasGroup(isAlias, isNew ? null : config.delegatesTo(), configId, allConfigs))
                .content(baseGroup(isAlias,
                        isNew ? null : config.type().name(),
                        isNew || config.provider() == null ? null : config.provider().name(),
                        isNew ? null : config.model(),
                        isNew ? null : config.baseUrl(),
                        isNew ? null : config.apiKey(),
                        id(), configId, lmStudio))
                .content(withHiddenIf(isAlias,
                        typeGroup(isNew ? LlmConfigType.CHAT : config.type(), config, null, allConfigs)))
                .content(withHiddenIf(isAlias, providerParamsGroup(
                        isNew ? null : config.provider(),
                        isNew ? ai.mindconnect.llm.domain.LlmConfigType.CHAT : config.type(),
                        config)))
                .action(UiAction.primary("save", "Save").icon("save")
                        .dispatch(isNew ? "POST" : "PUT",
                                  isNew ? "/admin/api/llm-configs"
                                        : "/admin/api/llm-configs/" + config.id().value(),
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
