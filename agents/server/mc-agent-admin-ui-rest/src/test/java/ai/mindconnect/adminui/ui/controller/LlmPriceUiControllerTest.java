package ai.mindconnect.adminui.ui.controller;

import ai.mindconnect.adminui.ui.component.LlmPricingComponent;
import ai.mindconnect.common.util.encryption.EncryptionHelper;
import ai.mindconnect.llm.adapter.lmstudio.LmStudioModelCatalog;
import ai.mindconnect.llm.adapter.memory.InMemoryLlmConfigRepository;
import ai.mindconnect.llm.adapter.memory.InMemoryLlmPriceRepository;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmPrice;
import ai.mindconnect.llm.service.LlmPriceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Pricing section of an LLM config: it shows under the config, saves a
 * period through its own form, says why a period is refused, and follows its
 * config through a rename and a delete.
 */
class LlmPriceUiControllerTest {

    private final InMemoryLlmConfigRepository configs = new InMemoryLlmConfigRepository();
    private final InMemoryLlmPriceRepository prices = new InMemoryLlmPriceRepository();
    private final LlmPriceService service = new LlmPriceService(prices, configs);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-24T10:00:00Z"), ZoneOffset.UTC);
    private final LlmPriceUiController controller = new LlmPriceUiController(configs, service, clock);
    private final LlmConfigUiController configController = new LlmConfigUiController(configs, null,
            EncryptionHelper.noEncryption(), new LmStudioModelCatalog()).withPrices(service);

    private LlmConfig claude;
    private LlmConfig alias;

    @BeforeEach
    void setUp() {
        claude = LlmConfig.claude("claude", "claude-sonnet-5", "key");
        alias = LlmConfig.alias("default", "claude");
        configs.save(claude);
        configs.save(alias);
    }

    @Test
    void theDetailAndEditPagesShowThePricingSectionUnderTheConfig() throws Exception {
        String detail = json(configController.detail(claude.id().value()).getBody());
        assertThat(detail).contains("llm-config-detail-" + claude.id().value())
                .contains(LlmPricingComponent.TABLE_ID).contains("Add price…")
                .contains("/admin/api/llm-configs/" + claude.id().value() + "/prices/new");
        assertThat(detail.indexOf("llm-config-detail-")).isLessThan(detail.indexOf(LlmPricingComponent.TABLE_ID));

        String edit = json(configController.editForm(claude.id().value()).getBody());
        assertThat(edit).contains("Edit LLM Config: claude").contains(LlmPricingComponent.TABLE_ID);
    }

    @Test
    void anAliasSaysWhoPricesIt() throws Exception {
        String detail = json(configController.detail(alias.id().value()).getBody());

        assertThat(detail).contains("Priced by claude").doesNotContain("Add price…");
    }

    @Test
    void addingAPeriodSavesItAndReplacesTheTable() throws Exception {
        String dialog = json(controller.newPrice(claude.id().value()).getBody());
        assertThat(dialog).contains(LlmPricingComponent.DIALOG_ID).contains("\"2026-09-24\"")
                .contains("inputPerMillion").contains("cachedInputPerMillion");

        String saved = json(controller.create(claude.id().value(), form("2026-01-01", "", "usd", "3", "15", "0,30"))
                .getBody());

        assertThat(prices.findByConfigName("claude")).singleElement().satisfies(p -> {
            assertThat(p.validFrom()).isEqualTo(LocalDate.parse("2026-01-01"));
            assertThat(p.validTo()).isNull();
            assertThat(p.currency()).isEqualTo("USD");
            assertThat(p.inputPerMillion()).isEqualByComparingTo("3");
            assertThat(p.cachedInputPerMillion()).isEqualByComparingTo("0.30");
        });
        assertThat(saved).contains(LlmPricingComponent.TABLE_ID).contains("current").contains("Price added");
    }

    @Test
    void anOverlapKeepsTheDialogOpenWithTheReasonAndWhatWasTyped() throws Exception {
        controller.create(claude.id().value(), form("2026-01-01", "", "USD", "3", "15", ""));

        String refused = json(controller.create(claude.id().value(),
                form("2026-06-01", "2026-12-01", "USD", "2", "10", "")).getBody());

        assertThat(refused).contains(LlmPricingComponent.DIALOG_ID).contains("must not overlap")
                .contains("2026-06-01").contains("\"2\"");
        assertThat(prices.findAll()).hasSize(1);
    }

    @Test
    void aPriceForAnAliasIsRefused() throws Exception {
        String refused = json(controller.create(alias.id().value(), form("2026-01-01", "", "USD", "3", "15", ""))
                .getBody());

        assertThat(refused).contains("is an alias").contains("uses the prices of 'claude'");
        assertThat(prices.findAll()).isEmpty();
    }

    @Test
    void aNumberThatIsNoneIsSaidSo() throws Exception {
        String refused = json(controller.create(claude.id().value(), form("2026-01-01", "", "USD", "three", "15", ""))
                .getBody());

        assertThat(refused).contains("Input price is not a number");
    }

    @Test
    void editingAndRemovingAPeriod() throws Exception {
        controller.create(claude.id().value(), form("2026-01-01", "", "USD", "3", "15", ""));
        LlmPrice stored = prices.findAll().get(0);

        String dialog = json(controller.edit(claude.id().value(), stored.id().value()).getBody());
        assertThat(dialog).contains("Edit price: claude").contains("\"3\"");

        controller.update(claude.id().value(), stored.id().value(), form("2026-01-01", "2026-07-01", "USD", "2.5", "12", ""));
        assertThat(prices.findById(stored.id()).orElseThrow().validTo()).isEqualTo(LocalDate.parse("2026-07-01"));
        assertThat(prices.findById(stored.id()).orElseThrow().inputPerMillion()).isEqualByComparingTo("2.5");

        String removed = json(controller.remove(claude.id().value(), stored.id().value()).getBody());
        assertThat(removed).contains("Price removed");
        assertThat(prices.findAll()).isEmpty();
    }

    @Test
    void aRenamedConfigKeepsItsPricesAndADeletedOneTakesThemAlong() {
        controller.create(claude.id().value(), form("2026-01-01", "", "USD", "3", "15", ""));
        Map<String, Object> rename = new HashMap<>(Map.of("name", "claude-5", "provider", "ANTHROPIC",
                "model", "claude-sonnet-5", "apiKey", "••••••••"));

        configController.update(claude.id().value(), rename);
        assertThat(prices.findByConfigName("claude")).isEmpty();
        assertThat(prices.findByConfigName("claude-5")).hasSize(1);

        configController.delete(claude.id().value());
        assertThat(prices.findAll()).isEmpty();
    }

    private static Map<String, Object> form(String from, String to, String currency, String in, String out,
                                            String cached) {
        Map<String, Object> raw = new HashMap<>();
        raw.put("validFrom", from);
        raw.put("validTo", to);
        raw.put("currency", currency);
        raw.put("inputPerMillion", in);
        raw.put("outputPerMillion", out);
        raw.put("cachedInputPerMillion", cached);
        return raw;
    }

    private static String json(Object node) throws Exception {
        return new ObjectMapper().findAndRegisterModules().writeValueAsString(node);
    }
}
