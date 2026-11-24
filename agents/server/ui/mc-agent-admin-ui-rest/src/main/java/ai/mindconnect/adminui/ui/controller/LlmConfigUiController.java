package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.adminui.ui.component.LlmConfigFormComponent;
import ai.mindconnect.common.StaleVersionException;
import ai.mindconnect.common.env.EnvVarResolver;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import org.springframework.beans.factory.ObjectProvider;
import ai.mindconnect.adminui.ui.page.LlmConfigDetailPage;
import ai.mindconnect.adminui.ui.page.LlmConfigFormPage;
import ai.mindconnect.adminui.ui.page.LlmConfigListPage;
import ai.mindconnect.adminui.ui.component.LlmConfigFormComponent.ModelChoices;
import ai.mindconnect.llm.adapter.ModelListing;
import ai.mindconnect.llm.adapter.ProviderModelCatalog;
import ai.mindconnect.llm.adapter.lmstudio.LmStudioModel;
import ai.mindconnect.llm.adapter.lmstudio.LmStudioModelCatalog;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmConfigId;
import ai.mindconnect.llm.domain.LlmConfigType;
import ai.mindconnect.llm.domain.LlmProvider;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.llm.service.LlmConfigService;
import ai.mindconnect.adminui.ui.component.LlmPricingComponent;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.chatui.ui.controller.FormBody;
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

    private final LlmConfigRepository repository;
    private final LlmConfigService configs;
    private final EncryptionHelper encryption;
    private final EnvVarResolver environment;
    private final LmStudioModelCatalog lmStudio;
    private final ProviderModelCatalog providerModels;

    public LlmConfigUiController(LlmConfigRepository repository,
                                 LlmConfigService configs,
                                 EncryptionHelper encryption,
                                 ObjectProvider<EnvVarResolver> environment,
                                 OkHttpClient httpClient,
                                 ObjectMapper objectMapper) {
        this.repository = repository;
        this.configs = configs;
        this.encryption = encryption;
        this.environment = environment.getIfAvailable(EnvVarResolver::system);
        this.lmStudio = new LmStudioModelCatalog(httpClient, objectMapper);
        this.providerModels = new ProviderModelCatalog(httpClient, objectMapper);
    }

    /** The Pricing section under a config, or null when this host keeps no prices. */
    private UiNode pricing(LlmConfig config) {
        return configs.prices().map(prices -> LlmPricingComponent.render(config, prices.pricesOf(config.name()),
                java.time.LocalDate.now(java.time.ZoneOffset.UTC),
                LlmPriceUiController.currentModel(config, environment))).orElse(null);
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
        LlmProvider provider = LlmConfigForm.providerFrom(new FormBody(raw));
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
     * <p>Picking a provider ({@code reason=provider}) fills in its endpoint —
     * {@code https://api.mistral.ai} for Mistral, {@code https://api.groq.com/openai}
     * for Groq — as long as the base URL is still empty or another provider's
     * default; a URL someone typed themselves (a proxy, a local server) is never
     * overwritten, and no other trigger replaces a URL that is there.
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
        LlmConfigType type = LlmConfigForm.typeFrom(body, config != null ? config.type() : LlmConfigType.CHAT);
        LlmProvider provider = LlmConfigForm.providerFrom(body);
        if (provider == null && config != null) provider = config.provider();
        String model = or(body.str("model"), config == null ? null : config.model());
        String apiKey = or(body.str("apiKey"), config == null ? null : config.apiKey());
        String baseUrl = baseUrlFor(provider,
                or(body.str("baseUrl"), config == null ? null : config.baseUrl()),
                "provider".equals(reason));

        ModelChoices choices = ModelChoices.none();
        LlmConfigFormComponent.ModelPrefill prefill = null;
        boolean modelPicked = "model".equals(reason);
        boolean contextEmpty = body.numOrNull("contextWindowTokens") == null;
        String pickedId = null;
        if (!isAlias && provider == LlmProvider.LM_STUDIO) {
            ModelListing<LmStudioModel> catalog = lmStudio.fetch(baseUrl);
            choices = ModelChoices.ofLmStudio(catalog);
            LmStudioModel picked = catalog.find(m -> m.id().equals(model));
            if (picked != null) {
                pickedId = picked.id();
                if (modelPicked || contextEmpty) {
                    prefill = LlmConfigFormComponent.ModelPrefill.of(picked);
                }
            }
        } else if (!isAlias && ProviderModelCatalog.supports(provider)) {
            ModelListing<ProviderModelCatalog.Model> catalog =
                    providerModels.fetch(provider, baseUrl,
                            // The form shows only the mask for a stored key: the listing is asked with the key itself.
                            LlmConfigForm.MASKED_KEY.equals(apiKey) && config != null ? config.apiKey() : apiKey,
                            environment, encryption);
            choices = ModelChoices.ofRemote(catalog);
            ProviderModelCatalog.Model picked = catalog.find(m -> m.id().equals(model));
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
     * when somebody chose it, the provider's own endpoint otherwise. Right
     * after the provider select changed, "somebody chose it" excludes another
     * provider's default — that is what sits in the field then, and leaving it
     * there would point Mistral at OpenAI. Any other re-render keeps a
     * non-empty URL as it is, even one that equals some provider's default: an
     * OpenAI-compatible config aimed at a local Ollama on
     * {@code http://localhost:11434} means exactly that.
     *
     * @param providerSwitched whether the provider select triggered this render
     */
    static String baseUrlFor(LlmProvider provider, String baseUrl, boolean providerSwitched) {
        if (provider == null) return baseUrl;
        boolean empty = baseUrl == null || baseUrl.isBlank();
        if (empty || (providerSwitched && LlmProvider.isADefaultBaseUrl(baseUrl))) {
            return provider.defaultBaseUrl();
        }
        return baseUrl;
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
                .map(c -> ResponseEntity.ok(new LlmConfigDetailPage(c, pricing(c)).render()))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/byName/{name}")
    public ResponseEntity<UiPage> detailByName(@PathVariable String name) {
        return repository.findByName(name)
                .map(c -> ResponseEntity.ok(new LlmConfigDetailPage(c, pricing(c)).render()))
                .orElse(ResponseEntity.notFound().build());
    }

    /** The edit form; for an LM Studio config with its server's model list. */
    @GetMapping("/{id}/edit")
    public ResponseEntity<UiPage> editForm(@PathVariable("id") String idValue) {
        LlmConfigId id = LlmConfigId.of(idValue);
        return repository.findById(id)
                .map(c -> ResponseEntity.ok(new LlmConfigFormPage(c, repository.findAll(),
                        modelChoicesFor(c)).pricing(pricing(c)).render()))
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
            return ModelChoices.ofLmStudio(lmStudio.fetch(config.baseUrl()));
        }
        if (!ProviderModelCatalog.supports(config.provider())) return ModelChoices.none();
        return ModelChoices.ofRemote(providerModels.fetch(config.provider(), config.baseUrl(), config.apiKey(),
                environment, encryption));
    }

    @PostMapping
    public UiPage create(@RequestBody Map<String, Object> raw) {
        configs.save(LlmConfigForm.toConfig(null, raw));
        return list();
    }

    /**
     * Saves the edit form against the version it was opened with; when the config
     * was saved since, the form stays on screen with a toast instead.
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable("id") String idValue,
                                    @RequestBody Map<String, Object> raw) {
        LlmConfig existing = repository.findById(LlmConfigId.of(idValue)).orElse(null);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            configs.save(LlmConfigForm.toConfig(existing, raw).withVersion(VersionedForms.version(new FormBody(raw))));
        } catch (StaleVersionException e) {
            return ResponseEntity.ok(VersionedForms.changedMeanwhile("LLM config '" + existing.name() + "'"));
        }
        return ResponseEntity.ok(list());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<UiPage> delete(@PathVariable("id") String idValue) {
        return configs.delete(LlmConfigId.of(idValue))
                ? ResponseEntity.ok(list()) : ResponseEntity.notFound().build();
    }

}
