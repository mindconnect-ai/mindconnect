package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.agentrest.service.LlmConfigTestService;
import ai.mindconnect.adminui.ui.component.LlmConfigFormComponent;
import ai.mindconnect.common.StaleVersionException;
import ai.mindconnect.common.util.EnvVarResolver;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.adminui.ui.component.LlmConfigTestComponent;
import ai.mindconnect.adminui.ui.page.LlmConfigDetailPage;
import ai.mindconnect.adminui.ui.page.LlmConfigFormPage;
import ai.mindconnect.adminui.ui.page.LlmConfigListPage;
import ai.mindconnect.adminui.ui.component.LlmConfigFormComponent.ModelChoices;
import ai.mindconnect.llm.adapter.ProviderModelCatalog;
import ai.mindconnect.llm.adapter.lmstudio.LmStudioModel;
import ai.mindconnect.llm.adapter.lmstudio.LmStudioModelCatalog;
import ai.mindconnect.llm.domain.LlmCapability;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmConfigId;
import ai.mindconnect.llm.domain.LlmConfigType;
import ai.mindconnect.llm.domain.LlmProvider;
import ai.mindconnect.llm.domain.RateLimitConfig;
import ai.mindconnect.llm.domain.RetryConfig;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.chatui.ui.controller.FormBody;
import ai.mindconnect.ui.model.UiDialog;
import ai.mindconnect.ui.model.UiPage;
import ai.mindconnect.ui.model.UiPatch;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/admin/api/llm-configs")
public class LlmConfigUiController {

    private static final String MASKED_KEY = "••••••••";

    /**
     * What an LM Studio config stores as its key. LM Studio takes no key, the
     * form has no field for one, and the OpenAI-compatible gateway still sends
     * a bearer header — so it sends this, as the bundled configs always have.
     */
    static final String LM_STUDIO_KEY = "lm-studio";

    private final LlmConfigRepository repository;
    private final LlmConfigTestService testService;
    private final EncryptionHelper encryption;
    private final LmStudioModelCatalog lmStudio;
    private final ProviderModelCatalog providerModels;

    @org.springframework.beans.factory.annotation.Autowired
    public LlmConfigUiController(LlmConfigRepository repository,
                                    LlmConfigTestService testService,
                                    EncryptionHelper encryption,
                                    OkHttpClient httpClient,
                                    ObjectMapper objectMapper) {
        this(repository, testService, encryption,
                new LmStudioModelCatalog(httpClient, objectMapper),
                new ProviderModelCatalog(httpClient, objectMapper));
    }

    /** For tests: catalogs that answer without an LM Studio or a provider account. */
    LlmConfigUiController(LlmConfigRepository repository,
                          LlmConfigTestService testService,
                          EncryptionHelper encryption,
                          LmStudioModelCatalog lmStudio) {
        this(repository, testService, encryption, lmStudio, new ProviderModelCatalog());
    }

    LlmConfigUiController(LlmConfigRepository repository,
                          LlmConfigTestService testService,
                          EncryptionHelper encryption,
                          LmStudioModelCatalog lmStudio,
                          ProviderModelCatalog providerModels) {
        this.repository = repository;
        this.testService = testService;
        this.encryption = encryption;
        this.lmStudio = lmStudio;
        this.providerModels = providerModels;
    }

    /**
     * Backs the form's trailing "Encrypt" helper: takes the form's current
     * field values and patches the apiKey field back with the key in its
     * stored {@code enc:} form. Already-tagged values ({@code enc:} /
     * {@code plain:}) and {@code ${VAR}} placeholders pass through unchanged
     * — a placeholder must stay a placeholder.
     */
    @PostMapping("/encrypt-key")
    public UiPatch encryptKey(@RequestParam("form") String formId,
                              @RequestParam(value = "id", required = false) String idValue,
                              @RequestBody Map<String, Object> raw) {
        String key = new FormBody(raw).str("apiKey");
        String shown = key;
        if (key != null && !key.isBlank()
                && !key.startsWith(EncryptionHelper.ENC)
                && !key.startsWith(EncryptionHelper.PLAIN)
                && !EnvVarResolver.containsPlaceholder(key)) {
            try {
                shown = EncryptionHelper.ENC + encryption.encrypt(key);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to encrypt API key", e);
            }
        }
        LlmConfigId id = idValue == null || idValue.isBlank() ? null : LlmConfigId.of(idValue);
        LlmProvider provider = providerFrom(new FormBody(raw));
        // The rebuilt field keeps the trigger that reloads the model list —
        // an encrypted key is exactly when the list becomes fetchable.
        String swapUrl = ProviderModelCatalog.supports(provider)
                ? LlmConfigFormComponent.swapUrl(formId, id) : null;
        return UiPatch.of().patch(UiPatch.Operation.replace("apiKey",
                LlmConfigFormComponent.apiKeyField(shown, formId, id, swapUrl)));
    }

    /**
     * isAlias, type, provider, base URL or model switched: re-render every
     * dependent group. Alias mode collapses the form to the delegation target;
     * provider mode shows the base fields plus the type- and provider-specific
     * groups. Values the admin already typed ride along in the submitted form
     * body and win over the stored config, so toggling never loses input.
     *
     * <p>Picking a provider fills in its endpoint — {@code https://api.mistral.ai}
     * for Mistral, {@code https://api.groq.com/openai} for Groq — as long as the
     * base URL is still empty or another provider's default; a URL someone typed
     * themselves (a proxy, a local server) is never overwritten.
     *
     * <p>The endpoint is then asked for its models, so the Model field becomes a
     * dropdown: LM Studio through its native API, every other provider but Azure
     * through its {@code /v1/models} listing. When the trigger was the model pick
     * itself ({@code reason=model}) — or the context window is still empty — the
     * picked model's context window (and, from LM Studio, its capabilities) are
     * written into the settings group. A pick also names a config that has no
     * name yet, after the model.
     */
    @PostMapping("/field-groups")
    public UiPatch fieldGroups(@RequestParam("form") String formId,
                               @RequestParam(value = "id", required = false) String idValue,
                               @RequestParam(value = "reason", required = false) String reason,
                               @RequestBody Map<String, Object> raw) {
        var body = new FormBody(raw);
        LlmConfigId id = idValue == null || idValue.isBlank()
                ? null : LlmConfigId.of(idValue);
        LlmConfig config = id == null ? null : repository.findById(id).orElse(null);
        boolean isAlias = body.bool("isAlias",
                config != null && config.isAlias());
        LlmConfigType type = typeFrom(body, config != null ? config.type() : LlmConfigType.CHAT);
        LlmProvider provider = providerFrom(body);
        if (provider == null && config != null) provider = config.provider();
        String model = or(body.str("model"), config == null ? null : config.model());
        String apiKey = or(body.str("apiKey"), config == null ? null : config.apiKey());
        String baseUrl = baseUrlFor(provider,
                or(body.str("baseUrl"), config == null ? null : config.baseUrl()));

        ModelChoices choices = ModelChoices.none();
        LlmConfigFormComponent.ModelPrefill prefill = null;
        boolean modelPicked = "model".equals(reason);
        boolean contextEmpty = body.numOrNull("contextWindowTokens") == null;
        String pickedId = null;
        if (!isAlias && provider == LlmProvider.LM_STUDIO) {
            LmStudioModelCatalog.Catalog catalog = lmStudio.fetch(baseUrl);
            choices = ModelChoices.of(catalog);
            LmStudioModel picked = catalog.find(model);
            if (picked != null) {
                pickedId = picked.id();
                if (modelPicked || contextEmpty) {
                    prefill = LlmConfigFormComponent.ModelPrefill.of(picked);
                }
            }
        } else if (!isAlias && ProviderModelCatalog.supports(provider)) {
            ProviderModelCatalog.Catalog catalog =
                    providerModels.fetch(probeConfig(provider, baseUrl, apiKey, config));
            choices = ModelChoices.of(catalog);
            ProviderModelCatalog.Model picked = catalog.find(model);
            if (picked != null) {
                pickedId = picked.id();
                if (modelPicked || contextEmpty) {
                    prefill = LlmConfigFormComponent.ModelPrefill.of(picked);
                }
            }
        }
        String suggestedName = null;
        String name = body.str("name");
        if (modelPicked && pickedId != null && (name == null || name.isBlank())) {
            suggestedName = LlmConfigFormComponent.suggestedName(pickedId);
        }
        UiPatch patch = UiPatch.of();
        if (suggestedName != null) {
            patch.patch(UiPatch.Operation.replace("name", LlmConfigFormComponent.nameField(suggestedName)));
        }
        return patch
                .patch(UiPatch.Operation.replace("llm-alias-cfg",
                        LlmConfigFormComponent.aliasGroup(isAlias,
                                or(body.str("delegatesTo"), config == null ? null : config.delegatesTo()),
                                config == null ? null : config.id(), repository.findAll())))
                .patch(UiPatch.Operation.replace("llm-base-cfg",
                        LlmConfigFormComponent.baseGroup(isAlias,
                                type.name(),
                                provider == null ? null : provider.name(),
                                model,
                                baseUrl,
                                apiKey,
                                formId, id, choices)))
                .patch(UiPatch.Operation.replace("llm-type-cfg",
                        LlmConfigFormComponent.withHiddenIf(isAlias,
                                LlmConfigFormComponent.typeGroup(type, config, prefill,
                                        repository.findAll()))))
                .patch(UiPatch.Operation.replace("llm-provider-params",
                        LlmConfigFormComponent.withHiddenIf(isAlias,
                                LlmConfigFormComponent.providerParamsGroup(provider, type, config))));
    }

    /** First non-null value — form input wins over the stored config. */
    private static String or(String formValue, String stored) {
        return formValue != null ? formValue : stored;
    }

    /**
     * The base URL the form should show for this provider: what is in the form
     * when somebody chose it, the provider's own endpoint otherwise. "Somebody
     * chose it" excludes another provider's default — that is what sits in the
     * field right after switching provider, and leaving it there would point
     * Mistral at OpenAI.
     */
    static String baseUrlFor(LlmProvider provider, String baseUrl) {
        if (provider == null) return baseUrl;
        if (LlmProvider.isADefaultBaseUrl(baseUrl)) return provider.defaultBaseUrl();
        return baseUrl;
    }

    /**
     * The config to ask for a model list: the form's provider, endpoint and
     * key, with the key taken from storage when the form shows only the mask,
     * and then resolved — {@code ${VAR}} expanded and {@code enc:} decrypted,
     * exactly as a gateway would before a call.
     */
    private LlmConfig probeConfig(LlmProvider provider, String baseUrl, String apiKey,
                                  LlmConfig stored) {
        String key = MASKED_KEY.equals(apiKey) && stored != null ? stored.apiKey() : apiKey;
        LlmConfig probe = new LlmConfig(LlmConfigId.random(), "probe", provider, null, baseUrl, key,
                0, 0, Map.of(), null, false, null, null, null, LlmConfigType.CHAT, null);
        try {
            return probe.resolved(encryption);
        } catch (RuntimeException e) {
            // A key that cannot be decrypted (a rotated secret) must not take
            // down the form — the listing simply fails and says why.
            return probe;
        }
    }

    /**
     * The key to store for a new config: what the form sent, except for LM
     * Studio, whose form has no key field — there it is {@link #LM_STUDIO_KEY}.
     */
    static String apiKeyFor(LlmProvider provider, String apiKey) {
        if (provider == LlmProvider.LM_STUDIO && (apiKey == null || apiKey.isBlank())) {
            return LM_STUDIO_KEY;
        }
        return apiKey;
    }

    /**
     * The key to store on update: the form's key, or the existing one when the
     * form sent the mask. For LM Studio the form has no key field: a config
     * that was LM Studio before keeps its key (a placeholder, say), one that
     * was just switched to LM Studio must not carry its old provider's secret
     * to a local server and gets {@link #LM_STUDIO_KEY}.
     */
    static String apiKeyForUpdate(LlmConfig existing, LlmProvider provider, String formKey) {
        if (provider == LlmProvider.LM_STUDIO) {
            boolean wasLmStudio = existing.provider() == LlmProvider.LM_STUDIO;
            String kept = wasLmStudio ? existing.apiKey() : null;
            return apiKeyFor(provider, kept);
        }
        return MASKED_KEY.equals(formKey) ? existing.apiKey() : formKey;
    }

    /** The form's provider, which a non-alias config must have. */
    private static LlmProvider requiredProvider(FormBody body) {
        String raw = body.str("provider");
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Provider is required — pick one before saving");
        }
        return LlmProvider.valueOf(raw);
    }

    /** The form's provider select, tolerant of missing/unknown values. */
    private static LlmProvider providerFrom(FormBody body) {
        String raw = body.str("provider");
        if (raw == null || raw.isBlank()) return null;
        try {
            return LlmProvider.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The form's type select, tolerant of missing/unknown values. */
    private static LlmConfigType typeFrom(FormBody body, LlmConfigType fallback) {
        String raw = body.str("type");
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return LlmConfigType.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    @GetMapping
    public UiPage list() {
        return new LlmConfigListPage(repository.findAll()).render();
    }

    @GetMapping("/new")
    public UiPage newForm() {
        return new LlmConfigFormPage(null, repository.findAll()).render();
    }

    @GetMapping("/{id}")
    public ResponseEntity<UiPage> detail(@PathVariable("id") String idValue) {
        LlmConfigId id = LlmConfigId.of(idValue);
        return repository.findById(id)
                .map(c -> ResponseEntity.ok(new LlmConfigDetailPage(c).render()))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/byName/{name}")
    public ResponseEntity<UiPage> detailByName(@PathVariable String name) {
        return repository.findByName(name)
                .map(c -> ResponseEntity.ok(new LlmConfigDetailPage(c).render()))
                .orElse(ResponseEntity.notFound().build());
    }

    /** The edit form; for an LM Studio config with its server's model list. */
    @GetMapping("/{id}/edit")
    public ResponseEntity<UiPage> editForm(@PathVariable("id") String idValue) {
        LlmConfigId id = LlmConfigId.of(idValue);
        return repository.findById(id)
                .map(c -> ResponseEntity.ok(new LlmConfigFormPage(c, repository.findAll(),
                        modelChoicesFor(c)).render()))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * The model list to open the edit form with: LM Studio's catalog for an LM
     * Studio config, the provider's own listing for anything that publishes
     * one, nothing for an alias or Azure OpenAI.
     */
    private ModelChoices modelChoicesFor(LlmConfig config) {
        if (config.isAlias()) return ModelChoices.none();
        if (config.provider() == LlmProvider.LM_STUDIO) {
            return ModelChoices.of(lmStudio.fetch(config.baseUrl()));
        }
        if (!ProviderModelCatalog.supports(config.provider())) return ModelChoices.none();
        return ModelChoices.of(providerModels.fetch(probeConfig(config.provider(),
                config.baseUrl(), config.apiKey(), config)));
    }

    @PostMapping
    public UiPage create(@RequestBody Map<String, Object> raw) {
        var body = new FormBody(raw);
        boolean isAlias = body.bool("isAlias", false);
        // Hidden fields still submit (by design) — an alias must not absorb
        // the invisible provider fields, and vice versa.
        LlmProvider provider = isAlias ? null : requiredProvider(body);
        var config = new LlmConfig(
                LlmConfigId.random(),
                body.str("name"),
                provider,
                isAlias ? null : body.str("model"),
                isAlias ? null : body.str("baseUrl"),
                // Defensive: the form pre-fills the masked sentinel for existing
                // keys; never persist the bullets themselves as an API key.
                isAlias ? null : apiKeyFor(provider, MASKED_KEY.equals(body.str("apiKey")) ? null : body.str("apiKey")),
                body.dbl("defaultTemperature", 0.7),
                body.num("maxOutputTokens", 4096),
                additionalParamsFrom(body, Map.of(), provider),
                body.numOrNull("contextWindowTokens"),
                isAlias,
                isAlias ? body.str("delegatesTo") : null,
                retryFrom(body),
                rateLimitFrom(body),
                typeFrom(body, LlmConfigType.CHAT),
                capabilitiesFrom(body),
                body.strList("fallbackModels"),
                null);
        repository.save(config);
        return list();
    }

    /**
     * Saves the edit form against the version it was opened with; when the config
     * was saved since, the form stays on screen with a toast instead.
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable("id") String idValue,
                                    @RequestBody Map<String, Object> raw) {
        LlmConfigId id = LlmConfigId.of(idValue);
        var body = new FormBody(raw);
        LlmConfig existing = repository.findById(id).orElse(null);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        boolean isAlias = body.bool("isAlias", existing.isAlias());
        LlmProvider provider = isAlias ? null : requiredProvider(body);
        var updated = new LlmConfig(
                existing.id(),
                body.str("name"),
                provider,
                isAlias ? null : body.str("model"),
                isAlias ? null : body.str("baseUrl"),
                isAlias ? null : apiKeyForUpdate(existing, provider, body.str("apiKey")),
                body.dbl("defaultTemperature", existing.defaultTemperature()),
                body.num("maxOutputTokens", existing.maxOutputTokens()),
                additionalParamsFrom(body, existing.additionalParams(), provider),
                body.numOrNull("contextWindowTokens"),
                isAlias,
                isAlias ? body.str("delegatesTo") : null,
                raw.containsKey("retryEnabled") ? retryFrom(body) : existing.retry(),
                raw.containsKey("maxConcurrentRequests")
                        ? rateLimitFrom(body) : existing.rateLimit(),
                raw.containsKey("type")
                        ? typeFrom(body, existing.type()) : existing.type(),
                raw.containsKey("capabilities")
                        ? capabilitiesFrom(body) : existing.capabilities(),
                raw.containsKey("fallbackModels")
                        ? body.strList("fallbackModels") : existing.fallbackModels(),
                null);
        try {
            repository.save(updated.withVersion(VersionedForms.version(body)));
        } catch (StaleVersionException e) {
            return ResponseEntity.ok(VersionedForms.changedMeanwhile("LLM config '" + existing.name() + "'"));
        }
        return ResponseEntity.ok(list());
    }

    /**
     * Opens the "Test config" dialog as a patch over whatever is on screen —
     * the page underneath is untouched. Empty body / no result yet; the
     * admin types a message (chat) or a text to embed (embedding config)
     * and submits via the form's POST.
     */
    @GetMapping("/{id}/test")
    public ResponseEntity<UiPatch> testDialog(@PathVariable("id") String idValue) {
        LlmConfigId id = LlmConfigId.of(idValue);
        return repository.findById(id)
                .map(c -> ResponseEntity.ok(testPatch(c, null, null)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Executes the test — one user-turn for a chat config, text → vector
     * for an embedding config — and re-renders the dialog in place with
     * the result underneath the form so the admin can re-send.
     */
    @PostMapping("/{id}/test")
    public ResponseEntity<UiPatch> runTest(@PathVariable("id") String idValue,
                                           @RequestBody Map<String, Object> raw) {
        LlmConfigId id = LlmConfigId.of(idValue);
        var body = new FormBody(raw);
        String message = body.str("message");
        return repository.findById(id)
                .map(c -> {
                    LlmConfigTestService.Result result = testService.test(c, message);
                    return ResponseEntity.ok(testPatch(c, message, result));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Executes a speech-to-text test: the dropped recording goes to the
     * transcription endpoint and the dialog comes back with the transcript.
     * A separate endpoint because this one takes multipart, not a form body.
     */
    @PostMapping(value = "/{id}/test-audio",
            consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UiPatch> runAudioTest(
            @PathVariable("id") String idValue,
            @RequestParam("audio") org.springframework.web.multipart.MultipartFile audio) {
        LlmConfigId id = LlmConfigId.of(idValue);
        return repository.findById(id)
                .map(c -> {
                    byte[] bytes;
                    try {
                        bytes = audio.getBytes();
                    } catch (java.io.IOException e) {
                        return ResponseEntity.ok(testPatch(c, null,
                                LlmConfigTestService.Result.error(
                                        "Could not read the upload: " + e.getMessage(), 0)));
                    }
                    String filename = audio.getOriginalFilename() == null
                            ? "recording.webm" : audio.getOriginalFilename();
                    LlmConfigTestService.Result result = testService.testTranscription(
                            c, bytes, filename, audio.getContentType());
                    return ResponseEntity.ok(testPatch(c, null, result));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * What a config under test really is. An alias carries no type of its
     * own — it points at another config by name, and the test follows that
     * name — so the type of the config behind it decides what the dialog
     * asks for.
     */
    private LlmConfigType effectiveType(LlmConfig config) {
        if (!config.isAlias()) return config.type();
        try {
            return repository.findResolvedByName(config.name())
                    .map(LlmConfig::type)
                    .orElse(config.type());
        } catch (RuntimeException e) {
            // A chain that is broken, circular or too deep throws. That is
            // worth seeing — but in the dialog, where the test reports it,
            // not as a 500 in place of the dialog.
            return config.type();
        }
    }

    /** Close is just "remove the overlay" — the page behind stays as-is. */
    @PostMapping("/test-dialog/close")
    public UiPatch closeTestDialog() {
        return UiPatch.of().patch(UiPatch.Operation.remove("llm-test-dialog"));
    }

    /**
     * The test dialog as a remove+append patch on the body-level dialog host
     * (same pattern as the tool-test dialogs): remove is a no-op on first
     * open and replaces the modal in place on a re-render; a null close-href
     * just removes the overlay without navigating. {@code message} preserves
     * the textarea content across a round-trip; {@code result} is the
     * outcome of the just-completed call (null for the initial open).
     */
    private UiPatch testPatch(LlmConfig c, String message, LlmConfigTestService.Result result) {
        var component = new LlmConfigTestComponent(c, effectiveType(c), message, result);
        UiDialog dialog = UiDialog.of(component.title(), null, component.render());
        dialog.setId("llm-test-dialog");
        return UiPatch.of()
                .patch(UiPatch.Operation.remove("llm-test-dialog"))
                .patch(UiPatch.Operation.append("sui-dialogs", dialog));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<UiPage> delete(@PathVariable("id") String idValue) {
        LlmConfigId id = LlmConfigId.of(idValue);
        if (repository.findById(id).isEmpty()) return ResponseEntity.notFound().build();
        repository.deleteById(id);
        return ResponseEntity.ok(list());
    }

    /**
     * Builds a {@link RetryConfig} from the flat {@code retry*} form fields.
     * When the "Retry on rate limit" toggle is off, returns {@code null} —
     * which means <em>no retry</em> (the gateway fails fast on 429/529).
     */
    private static RetryConfig retryFrom(FormBody body) {
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
     * driven by the provider's {@link ai.mindconnect.llm.domain.AdditionalParamSpec}
     * catalog; specs of OTHER providers are dropped so switching the provider
     * never leaves stale parameters behind. Manually-added keys unknown to any
     * catalog pass through untouched.
     */
    private static Map<String, Object> additionalParamsFrom(FormBody body, Map<String, Object> existing,
                                                            LlmProvider provider) {
        Map<String, Object> params = new java.util.HashMap<>(
                existing != null ? existing : Map.of());
        java.util.Set<String> mine = provider == null ? java.util.Set.of()
                : provider.additionalParams().stream()
                        .map(ai.mindconnect.llm.domain.AdditionalParamSpec::key)
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

    private static void applyParam(FormBody body, Map<String, Object> params, String key) {
        String v = body.str(key);
        if (v == null) return;            // field absent from form → leave untouched
        if (v.isBlank()) params.remove(key);
        else params.put(key, v);
    }

    /**
     * The capability multiselect as a set — unknown values (a stale form, a
     * renamed constant) are dropped rather than failing the save.
     */
    private static java.util.Set<LlmCapability> capabilitiesFrom(FormBody body) {
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
    private static RateLimitConfig rateLimitFrom(FormBody body) {
        Integer max = body.numOrNull("maxConcurrentRequests");
        if (max == null || max < 1) return null;
        return RateLimitConfig.of(max);
    }
}
