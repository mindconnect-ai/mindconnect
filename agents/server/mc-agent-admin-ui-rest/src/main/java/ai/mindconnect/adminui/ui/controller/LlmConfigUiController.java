package ai.mindconnect.adminui.ui.controller;


import ai.mindconnect.agentrest.service.LlmConfigTestService;
import ai.mindconnect.adminui.ui.component.LlmConfigFormComponent;
import ai.mindconnect.common.util.EnvVarResolver;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.adminui.ui.component.LlmConfigTestComponent;
import ai.mindconnect.adminui.ui.page.LlmConfigDetailPage;
import ai.mindconnect.adminui.ui.page.LlmConfigFormPage;
import ai.mindconnect.adminui.ui.page.LlmConfigListPage;
import ai.mindconnect.llm.adapter.lmstudio.LmStudioModel;
import ai.mindconnect.llm.adapter.lmstudio.LmStudioModelCatalog;
import ai.mindconnect.llm.domain.LlmCapability;
import ai.mindconnect.llm.domain.LlmConfig;
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
import java.util.UUID;

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

    @org.springframework.beans.factory.annotation.Autowired
    public LlmConfigUiController(LlmConfigRepository repository,
                                    LlmConfigTestService testService,
                                    EncryptionHelper encryption,
                                    OkHttpClient httpClient,
                                    ObjectMapper objectMapper) {
        this(repository, testService, encryption, new LmStudioModelCatalog(httpClient, objectMapper));
    }

    /** For tests: a catalog that answers without an LM Studio. */
    LlmConfigUiController(LlmConfigRepository repository,
                          LlmConfigTestService testService,
                          EncryptionHelper encryption,
                          LmStudioModelCatalog lmStudio) {
        this.repository = repository;
        this.testService = testService;
        this.encryption = encryption;
        this.lmStudio = lmStudio;
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
        return UiPatch.of().patch(UiPatch.Operation.replace("apiKey",
                LlmConfigFormComponent.apiKeyField(shown, formId)));
    }

    /**
     * isAlias, type, provider, base URL or model switched: re-render every
     * dependent group. Alias mode collapses the form to the delegation target;
     * provider mode shows the base fields plus the type- and provider-specific
     * groups. Values the admin already typed ride along in the submitted form
     * body and win over the stored config, so toggling never loses input.
     *
     * <p>With LM Studio as the provider the base URL is asked for its models
     * (so the model field becomes a dropdown), and when the trigger was the
     * model pick itself ({@code reason=model}) — or the context window is
     * still empty — the picked model's context length and capabilities are
     * written into the settings group. A pick also names a config that has
     * no name yet, after the model.
     */
    @PostMapping("/field-groups")
    public UiPatch fieldGroups(@RequestParam("form") String formId,
                               @RequestParam(value = "id", required = false) UUID id,
                               @RequestParam(value = "reason", required = false) String reason,
                               @RequestBody Map<String, Object> raw) {
        var body = new FormBody(raw);
        LlmConfig config = id == null ? null : repository.findById(id).orElse(null);
        boolean isAlias = body.bool("isAlias",
                config != null && config.isAlias());
        LlmConfigType type = typeFrom(body, config != null ? config.type() : LlmConfigType.CHAT);
        LlmProvider provider = providerFrom(body);
        if (provider == null && config != null) provider = config.provider();
        String model = or(body.str("model"), config == null ? null : config.model());
        String baseUrl = or(body.str("baseUrl"), config == null ? null : config.baseUrl());

        LmStudioModelCatalog.Catalog catalog = null;
        LlmConfigFormComponent.LmStudioPrefill prefill = null;
        String suggestedName = null;
        if (!isAlias && provider == LlmProvider.LM_STUDIO) {
            catalog = lmStudio.fetch(baseUrl);
            LmStudioModel picked = catalog.find(model);
            boolean modelPicked = "model".equals(reason);
            boolean contextEmpty = body.numOrNull("contextWindowTokens") == null;
            if (picked != null && (modelPicked || contextEmpty)) {
                prefill = LlmConfigFormComponent.LmStudioPrefill.of(picked);
            }
            String name = body.str("name");
            if (modelPicked && picked != null && (name == null || name.isBlank())) {
                suggestedName = LlmConfigFormComponent.suggestedName(picked.id());
            }
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
                                or(body.str("apiKey"), config == null ? null : config.apiKey()),
                                formId, id, catalog)))
                .patch(UiPatch.Operation.replace("llm-type-cfg",
                        LlmConfigFormComponent.withHiddenIf(isAlias,
                                LlmConfigFormComponent.typeGroup(type, config, prefill))))
                .patch(UiPatch.Operation.replace("llm-provider-params",
                        LlmConfigFormComponent.withHiddenIf(isAlias,
                                LlmConfigFormComponent.providerParamsGroup(provider, type, config))));
    }

    /** First non-null value — form input wins over the stored config. */
    private static String or(String formValue, String stored) {
        return formValue != null ? formValue : stored;
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
    public ResponseEntity<UiPage> detail(@PathVariable UUID id) {
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
    public ResponseEntity<UiPage> editForm(@PathVariable UUID id) {
        return repository.findById(id)
                .map(c -> ResponseEntity.ok(new LlmConfigFormPage(c, repository.findAll(),
                        lmStudioCatalogFor(c)).render()))
                .orElse(ResponseEntity.notFound().build());
    }

    /** The LM Studio catalog for an LM Studio config, {@code null} for any other. */
    private LmStudioModelCatalog.Catalog lmStudioCatalogFor(LlmConfig config) {
        if (config.isAlias() || config.provider() != LlmProvider.LM_STUDIO) return null;
        return lmStudio.fetch(config.baseUrl());
    }

    @PostMapping
    public UiPage create(@RequestBody Map<String, Object> raw) {
        var body = new FormBody(raw);
        boolean isAlias = body.bool("isAlias", false);
        // Hidden fields still submit (by design) — an alias must not absorb
        // the invisible provider fields, and vice versa.
        LlmProvider provider = isAlias ? null : requiredProvider(body);
        var config = new LlmConfig(
                UUID.randomUUID(),
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
                capabilitiesFrom(body));
        repository.save(config);
        return list();
    }

    @PutMapping("/{id}")
    public ResponseEntity<UiPage> update(@PathVariable UUID id,
                                         @RequestBody Map<String, Object> raw) {
        var body = new FormBody(raw);
        return repository.findById(id)
                .map(existing -> {
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
                                    ? capabilitiesFrom(body) : existing.capabilities());
                    repository.save(updated);
                    return ResponseEntity.ok(list());
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Opens the "Test config" dialog as a patch over whatever is on screen —
     * the page underneath is untouched. Empty body / no result yet; the
     * admin types a message (chat) or a text to embed (embedding config)
     * and submits via the form's POST.
     */
    @GetMapping("/{id}/test")
    public ResponseEntity<UiPatch> testDialog(@PathVariable UUID id) {
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
    public ResponseEntity<UiPatch> runTest(@PathVariable UUID id,
                                           @RequestBody Map<String, Object> raw) {
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
            @PathVariable UUID id,
            @RequestParam("audio") org.springframework.web.multipart.MultipartFile audio) {
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
    public ResponseEntity<UiPage> delete(@PathVariable UUID id) {
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
