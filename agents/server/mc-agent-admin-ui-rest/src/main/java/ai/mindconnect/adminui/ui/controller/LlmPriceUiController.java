package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.adminui.ui.component.LlmPricingComponent;
import ai.mindconnect.adminui.ui.component.LlmPricingComponent.Draft;
import ai.mindconnect.chatui.ui.controller.FormBody;
import ai.mindconnect.common.env.EnvVarResolver;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmConfigId;
import ai.mindconnect.llm.domain.LlmPrice;
import ai.mindconnect.llm.domain.LlmPriceId;
import ai.mindconnect.llm.port.out.LlmConfigRepository;
import ai.mindconnect.llm.port.out.LlmPriceRepository;
import ai.mindconnect.llm.service.LlmPriceService;
import ai.mindconnect.ui.model.UiDialog;
import ai.mindconnect.ui.model.UiNode;
import ai.mindconnect.ui.model.UiPatch;
import ai.mindconnect.ui.model.UiToast;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The "Pricing" section of an LLM config: add, edit and remove its price
 * periods. Its own endpoints under the config's, because a price is its own
 * entity — the config form never carries it.
 *
 * <p>Add and edit open a dialog; a save that breaks a rule (overlapping
 * periods, a price for an alias, a malformed number) keeps the dialog open
 * with the reason. Every change answers with the section's table replaced
 * in place.
 *
 * <p>A price is for one model of the config. The dialog's Model field is
 * prefilled with the model the config serves now — a
 * {@code ${OPENAI_MODEL:gpt-5.4-mini}} placeholder resolved the way the
 * gateways resolve it ({@link LlmConfig#resolvedModel}), from the same
 * {@link EnvVarResolver} — and any other name may be typed.
 */
@RestController
@RequestMapping("/admin/api/llm-configs")
public class LlmPriceUiController {

    private final LlmConfigRepository configs;
    private final LlmPriceService prices;
    private final Clock clock;
    private final EnvVarResolver environment;

    @Autowired
    public LlmPriceUiController(LlmConfigRepository configs, LlmPriceRepository prices,
                                ObjectProvider<EnvVarResolver> environment) {
        this(configs, new LlmPriceService(prices, configs), Clock.systemUTC(),
                environment.getIfAvailable(EnvVarResolver::system));
    }

    LlmPriceUiController(LlmConfigRepository configs, LlmPriceService prices, Clock clock,
                         EnvVarResolver environment) {
        this.configs = Objects.requireNonNull(configs, "configs");
        this.prices = Objects.requireNonNull(prices, "prices");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    /**
     * The model {@code config} serves now, placeholders resolved as a call resolves them;
     * null for an alias, a config without a model, or a placeholder nothing resolves.
     */
    static String currentModel(LlmConfig config, EnvVarResolver environment) {
        if (config.isAlias() || config.model() == null) return null;
        try {
            String model = config.resolvedModel(environment);
            return model == null || model.isBlank() ? null : model.strip();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String currentModel(LlmConfig config) {
        return currentModel(config, environment);
    }

    /** The section on its own — what the detail and edit pages embed. */
    @GetMapping("/{id}/prices")
    public ResponseEntity<UiNode> section(@PathVariable("id") String configId) {
        return config(configId)
                .map(c -> ResponseEntity.ok(LlmPricingComponent.render(c, prices.pricesOf(c.name()), today(),
                        currentModel(c))))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/prices/new")
    public ResponseEntity<UiPatch> newPrice(@PathVariable("id") String configId) {
        return config(configId)
                .map(c -> ResponseEntity.ok(dialog("Add price: " + c.name(),
                        LlmPricingComponent.form(c, Draft.empty(today(), currentModel(c)),
                                LlmPricingComponent.api(c), true, null))))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/prices")
    public ResponseEntity<UiPatch> create(@PathVariable("id") String configId,
                                          @RequestBody Map<String, Object> raw) {
        Optional<LlmConfig> config = config(configId);
        if (config.isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(save(config.get(), LlmPriceId.random(), raw, true));
    }

    @GetMapping("/{id}/prices/{priceId}/edit")
    public ResponseEntity<UiPatch> edit(@PathVariable("id") String configId,
                                        @PathVariable("priceId") String priceId) {
        Optional<LlmConfig> config = config(configId);
        if (config.isEmpty()) return ResponseEntity.notFound().build();
        LlmConfig c = config.get();
        return ResponseEntity.ok(ownPrice(c, priceId)
                .map(p -> dialog("Edit price: " + c.name(), LlmPricingComponent.form(c, Draft.of(p, currentModel(c)),
                        LlmPricingComponent.api(c) + "/" + p.id().value(), false, null)))
                .orElseGet(() -> refreshed(c).toast(gone())));
    }

    @PostMapping("/{id}/prices/{priceId}")
    public ResponseEntity<UiPatch> update(@PathVariable("id") String configId,
                                          @PathVariable("priceId") String priceId,
                                          @RequestBody Map<String, Object> raw) {
        Optional<LlmConfig> config = config(configId);
        if (config.isEmpty()) return ResponseEntity.notFound().build();
        LlmConfig c = config.get();
        Optional<LlmPrice> stored = ownPrice(c, priceId);
        if (stored.isEmpty()) return ResponseEntity.ok(refreshed(c).toast(gone()));
        return ResponseEntity.ok(save(c, stored.get().id(), raw, false));
    }

    @DeleteMapping("/{id}/prices/{priceId}")
    public ResponseEntity<UiPatch> remove(@PathVariable("id") String configId,
                                          @PathVariable("priceId") String priceId) {
        Optional<LlmConfig> config = config(configId);
        if (config.isEmpty()) return ResponseEntity.notFound().build();
        LlmConfig c = config.get();
        Optional<LlmPrice> stored = ownPrice(c, priceId);
        if (stored.isEmpty()) return ResponseEntity.ok(refreshed(c).toast(gone()));
        prices.delete(stored.get().id());
        return ResponseEntity.ok(refreshed(c).toast(UiToast.success("The period "
                + ai.mindconnect.llm.domain.LlmPrices.period(stored.get()) + " is gone.").title("Price removed")));
    }

    @PostMapping("/prices/dialog/close")
    public UiPatch close() {
        return UiPatch.of().patch(UiPatch.Operation.remove(LlmPricingComponent.DIALOG_ID));
    }

    // ── internals ───────────────────────────────────────────────────────────

    private UiPatch save(LlmConfig config, LlmPriceId id, Map<String, Object> raw, boolean isNew) {
        FormBody body = new FormBody(raw);
        Draft draft = new Draft(body.str("model"), body.str("validFrom"), body.str("validTo"), body.str("currency"),
                body.str("inputPerMillion"), body.str("outputPerMillion"), body.str("cachedInputPerMillion"));
        String target = isNew ? LlmPricingComponent.api(config) : LlmPricingComponent.api(config) + "/" + id.value();
        String title = (isNew ? "Add price: " : "Edit price: ") + config.name();
        try {
            LlmPrice price = new LlmPrice(id, config.name(), LlmPrice.requireModel(draft.model()),
                    date(draft.validFrom(), "Valid from"), date(draft.validTo(), "Valid to"),
                    draft.currency(),
                    rate(draft.input(), "Input"), rate(draft.output(), "Output"),
                    rate(draft.cachedInput(), "Cached input"));
            prices.save(price);
            return refreshed(config).toast(UiToast.success("The period "
                    + ai.mindconnect.llm.domain.LlmPrices.period(price) + " of " + price.model() + " is saved.")
                    .title(isNew ? "Price added" : "Price saved"));
        } catch (IllegalArgumentException e) {
            return dialog(title, LlmPricingComponent.form(config, draft, target, isNew, e.getMessage()));
        }
    }

    private Optional<LlmConfig> config(String configId) {
        return configs.findById(LlmConfigId.of(configId));
    }

    /** A period of this config — an id of another config's period is treated as unknown. */
    private Optional<LlmPrice> ownPrice(LlmConfig config, String priceId) {
        return prices.find(LlmPriceId.of(priceId)).filter(p -> p.configName().equals(config.name()));
    }

    private UiPatch refreshed(LlmConfig config) {
        return UiPatch.of()
                .patch(UiPatch.Operation.remove(LlmPricingComponent.DIALOG_ID))
                .patch(UiPatch.Operation.replace(LlmPricingComponent.TABLE_ID,
                        LlmPricingComponent.table(config, prices.pricesOf(config.name()), today())));
    }

    private static UiPatch dialog(String title, UiNode body) {
        UiDialog dialog = UiDialog.of(title, null, body);
        dialog.setId(LlmPricingComponent.DIALOG_ID);
        return UiPatch.of()
                .patch(UiPatch.Operation.remove(LlmPricingComponent.DIALOG_ID))
                .patch(UiPatch.Operation.append("sui-dialogs", dialog));
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    /** A day from the form's date input ({@code 2026-09-24}); a datetime value keeps its date. */
    static LocalDate date(String value, String label) {
        if (value == null || value.isBlank()) return null;
        String v = value.strip();
        try {
            return LocalDate.parse(v.length() > 10 ? v.substring(0, 10) : v);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(label + " is not a date: '" + v + "'");
        }
    }

    /** A rate as typed; a comma is read as the decimal point, blank as not given. */
    static BigDecimal rate(String value, String label) {
        if (value == null || value.isBlank()) return null;
        String v = value.strip().replace(" ", "");
        if (v.indexOf(',') >= 0 && v.indexOf('.') < 0) v = v.replace(',', '.');
        try {
            return new BigDecimal(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " price is not a number: '" + value.strip() + "'");
        }
    }

    private static UiToast gone() {
        return UiToast.info("That price period is not there any more.").title("Nothing to change");
    }
}
