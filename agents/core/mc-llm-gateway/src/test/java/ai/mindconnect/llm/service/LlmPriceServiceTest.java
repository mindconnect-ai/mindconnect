package ai.mindconnect.llm.service;

import ai.mindconnect.llm.adapter.memory.InMemoryLlmConfigRepository;
import ai.mindconnect.llm.adapter.memory.InMemoryLlmPriceRepository;
import ai.mindconnect.llm.domain.LlmConfig;
import ai.mindconnect.llm.domain.LlmPrice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmPriceServiceTest {

    InMemoryLlmConfigRepository configs;
    InMemoryLlmPriceRepository prices;
    LlmPriceService service;

    @BeforeEach
    void setUp() {
        configs = new InMemoryLlmConfigRepository();
        prices = new InMemoryLlmPriceRepository();
        service = new LlmPriceService(prices, configs);
        configs.save(LlmConfig.claude("claude", "claude-sonnet-5", "key"));
        configs.save(LlmConfig.alias("default", "claude"));
    }

    static LlmPrice price(String config, String from, String to) {
        return price(config, "claude-sonnet-5", from, to);
    }

    static LlmPrice price(String config, String model, String from, String to) {
        return LlmPrice.of(config, model, LocalDate.parse(from), to == null ? null : LocalDate.parse(to), "USD",
                new BigDecimal("3"), new BigDecimal("15"), null);
    }

    @Test
    void periodsThatFollowEachOtherAreSaved() {
        service.save(price("claude", "2026-01-01", "2026-07-01"));
        service.save(price("claude", "2026-07-01", null));

        assertThat(service.pricesOf("claude")).hasSize(2);
    }

    @Test
    void anOverlappingPeriodIsRefusedWithBothPeriodsNamed() {
        service.save(price("claude", "2026-01-01", null));

        assertThatThrownBy(() -> service.save(price("claude", "2026-06-01", "2026-09-01")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2026-06-01 – 2026-09-01")
                .hasMessageContaining("overlaps 2026-01-01 – open of claude-sonnet-5 on 'claude'")
                .hasMessageContaining("must not overlap");
        assertThat(service.pricesOf("claude")).hasSize(1);
    }

    @Test
    void editingAPeriodDoesNotClashWithItself() {
        LlmPrice stored = service.save(price("claude", "2026-01-01", null));
        LlmPrice moved = new LlmPrice(stored.id(), "claude", "claude-sonnet-5", LocalDate.parse("2026-02-01"), null, "USD",
                BigDecimal.ONE, BigDecimal.TEN, null);

        service.save(moved);

        assertThat(service.pricesOf("claude")).containsExactly(moved);
    }

    @Test
    void anAliasIsRefusedAndToldToUseItsTarget() {
        assertThatThrownBy(() -> service.save(price("default", "2026-01-01", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'default' is an alias")
                .hasMessageContaining("uses the prices of 'claude'");
        assertThat(prices.findAll()).isEmpty();
    }

    @Test
    void anUnknownConfigIsRefused() {
        assertThatThrownBy(() -> service.save(price("nobody", "2026-01-01", null)))
                .hasMessageContaining("no LLM config named 'nobody'");
    }

    @Test
    void aRenameTakesThePricesAlongAndADeleteRemovesThem() {
        service.save(price("claude", "2026-01-01", "2026-07-01"));
        service.save(price("claude", "2026-07-01", null));
        prices.save(price("gpt", "2026-01-01", null));

        service.configRenamed("claude", "claude-5");
        assertThat(service.pricesOf("claude")).isEmpty();
        assertThat(service.pricesOf("claude-5")).hasSize(2);

        service.configDeleted("claude-5");
        assertThat(service.pricesOf("claude-5")).isEmpty();
        assertThat(prices.findAll()).extracting(LlmPrice::configName).containsExactly("gpt");
    }

    @Test
    void periodsOverlapPerModelOnly() {
        service.save(price("claude", "claude-sonnet-5", "2026-01-01", null));

        service.save(price("claude", "claude-opus-5", "2026-01-01", null));
        assertThatThrownBy(() -> service.save(price("claude", "Claude-Sonnet-5", "2026-03-01", null)))
                .hasMessageContaining("must not overlap");

        assertThat(service.pricesOf("claude")).extracting(LlmPrice::model)
                .containsExactlyInAnyOrder("claude-sonnet-5", "claude-opus-5");
    }

    @Test
    void aPriceWithoutAModelIsRefused() {
        LlmPrice noModel = new LlmPrice(ai.mindconnect.llm.domain.LlmPriceId.random(), "claude", " ",
                LocalDate.parse("2026-01-01"), null, "USD", BigDecimal.ONE, BigDecimal.ONE, null);

        assertThatThrownBy(() -> service.save(noModel)).hasMessageContaining("needs the model");
        assertThat(prices.findAll()).isEmpty();
    }

    @Test
    void aRenameKeepsEachPricesModel() {
        service.save(price("claude", "claude-sonnet-5", "2026-01-01", null));
        service.save(price("claude", "claude-opus-5", "2026-01-01", null));

        service.configRenamed("claude", "claude-5");

        assertThat(service.pricesOf("claude-5")).extracting(LlmPrice::model)
                .containsExactlyInAnyOrder("claude-sonnet-5", "claude-opus-5");
    }
}
