package ai.mindconnect.adminui.ui.controller;


import ai.mindconnect.agentrest.service.LlmConfigTestService;
import ai.mindconnect.adminui.ui.component.LlmConfigFormComponent;
import ai.mindconnect.common.util.EnvVarResolver;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.adminui.ui.component.LlmConfigTestComponent;
import ai.mindconnect.adminui.ui.page.LlmConfigDetailPage;
import ai.mindconnect.adminui.ui.page.LlmConfigFormPage;
import ai.mindconnect.adminui.ui.page.LlmConfigListPage;
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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/admin/api/llm-configs")
public class LlmConfigUiController {

    private static final String MASKED_KEY = "••••••••";

    private final LlmConfigRepository repository;
    private final LlmConfigTestService testService;
    private final EncryptionHelper encryption;

    public LlmConfigUiController(LlmConfigRepository repository,
                                    LlmConfigTestService testService,
                                    EncryptionHelper encryption) {
        this.repository = repository;
        this.testService = testService;
        this.encryption = encryption;
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
     * isAlias, type or provider switched: re-render every dependent group.
     * Alias mode collapses the form to the delegation target; provider mode
     * shows the base fields plus the type- and provider-specific groups.
     * Values the admin already typed ride along in the submitted form body
     * and win over the stored config, so toggling never loses input.
     */
    @PostMapping("/field-groups")
    public UiPatch fieldGroups(@RequestParam("form") String formId,
                               @RequestParam(value = "id", required = false) UUID id,
                               @RequestBody Map<String, Object> raw) {
        var body = new FormBody(raw);
        LlmConfig config = id == null ? null : repository.findById(id).orElse(null);
        boolean isAlias = body.bool("isAlias",
                config != null && config.isAlias());
        LlmConfigType type = typeFrom(body, config != null ? config.type() : LlmConfigType.CHAT);
        LlmProvider provider = providerFrom(body);
        if (provider == null && config != null) provider = config.provider();
        return UiPatch.of()
                .patch(UiPatch.Operation.replace("llm-alias-cfg",
                        LlmConfigFormComponent.aliasGroup(isAlias,
                                or(body.str("delegatesTo"), config == null ? null : config.delegatesTo()),
                                config == null ? null : config.id(), repository.findAll())))
                .patch(UiPatch.Operation.replace("llm-base-cfg",
                        LlmConfigFormComponent.baseGroup(isAlias,
                                type.name(),
                                provider == null ? null : provider.name(),
                                or(body.str("model"), config == null ? null : config.model()),
                                or(body.str("baseUrl"), config == null ? null : config.baseUrl()),
                                or(body.str("apiKey"), config == null ? null : config.apiKey()),
                                formId, id)))
                .patch(UiPatch.Operation.replace("llm-type-cfg",
                        LlmConfigFormComponent.withHiddenIf(isAlias,
                                LlmConfigFormComponent.typeGroup(type == LlmConfigType.EMBEDDING, config))))
                .patch(UiPatch.Operation.replace("llm-provider-params",
                        LlmConfigFormComponent.withHiddenIf(isAlias,
                                LlmConfigFormComponent.providerParamsGroup(provider, type, config))));
    }

    /** First non-null value — form input wins over the stored config. */
    private static String or(String formValue, String stored) {
        return formValue != null ? formValue : stored;
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

    @GetMapping("/{id}/edit")
    public ResponseEntity<UiPage> editForm(@PathVariable UUID id) {
        return repository.findById(id)
                .map(c -> ResponseEntity.ok(new LlmConfigFormPage(c, repository.findAll()).render()))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public UiPage create(@RequestBody Map<String, Object> raw) {
        var body = new FormBody(raw);
        boolean isAlias = body.bool("isAlias", false);
        // Hidden fields still submit (by design) — an alias must not absorb
        // the invisible provider fields, and vice versa.
        LlmProvider provider = isAlias ? null : LlmProvider.valueOf(body.str("provider"));
        var config = new LlmConfig(
                UUID.randomUUID(),
                body.str("name"),
                provider,
                isAlias ? null : body.str("model"),
                isAlias ? null : body.str("baseUrl"),
                // Defensive: the form pre-fills the masked sentinel for existing
                // keys; never persist the bullets themselves as an API key.
                isAlias || MASKED_KEY.equals(body.str("apiKey")) ? null : body.str("apiKey"),
                body.dbl("defaultTemperature", 0.7),
                body.num("maxOutputTokens", 4096),
                additionalParamsFrom(body, Map.of(), provider),
                body.numOrNull("contextWindowTokens"),
                isAlias,
                isAlias ? body.str("delegatesTo") : null,
                retryFrom(body),
                rateLimitFrom(body),
                typeFrom(body, LlmConfigType.CHAT));
        repository.save(config);
        return list();
    }

    @PutMapping("/{id}")
    public ResponseEntity<UiPage> update(@PathVariable UUID id,
                                         @RequestBody Map<String, Object> raw) {
        var body = new FormBody(raw);
        return repository.findById(id)
                .map(existing -> {
                    String apiKey = MASKED_KEY.equals(body.str("apiKey"))
                            ? existing.apiKey()
                            : body.str("apiKey");
                    boolean isAlias = body.bool("isAlias", existing.isAlias());
                    LlmProvider provider = isAlias ? null : LlmProvider.valueOf(body.str("provider"));
                    var updated = new LlmConfig(
                            existing.id(),
                            body.str("name"),
                            provider,
                            isAlias ? null : body.str("model"),
                            isAlias ? null : body.str("baseUrl"),
                            isAlias ? null : apiKey,
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
                                    ? typeFrom(body, existing.type()) : existing.type());
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
        var component = new LlmConfigTestComponent(c, message, result);
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
     * Builds a {@link RateLimitConfig} from the {@code maxConcurrentRequests}
     * form field. A blank/absent/≤0 value means no throttling → {@code null}.
     */
    private static RateLimitConfig rateLimitFrom(FormBody body) {
        Integer max = body.numOrNull("maxConcurrentRequests");
        if (max == null || max < 1) return null;
        return RateLimitConfig.of(max);
    }
}
